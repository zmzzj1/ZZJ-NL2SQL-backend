package com.queryforge.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.platform.config.LlmProperties;
import com.queryforge.platform.dto.GenerateSqlRequest;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SQL 生成 SSE 事件改写")
class SqlGenerationServiceTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final long CONVERSATION_ID = 20L;
    private static final long MESSAGE_ID = 30L;
    private static final String USER_INPUT = "统计订单数量";
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ConnectionService connectionService;

    @Mock
    private ConversationService conversationService;

    private FakeLlmClient client;

    @BeforeEach
    void setUp() {
        client = new FakeLlmClient();
    }

    @Test
    @DisplayName("按 start -> delta -> complete 顺序输出，并使用服务端读取的 Schema")
    void writeStream_shouldEmitStartDeltasAndComplete() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        client.lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"SELECT \"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"COUNT(*) FROM shop_orders\"}}]}",
                "data: [DONE]");
        SqlGenerationService service = serviceWith("test-key");

        String output = generate(service);

        assertThat(output).contains("event: start", "event: delta", "event: complete");
        assertThat(indexOf(output, "event: start")).isLessThan(indexOf(output, "event: delta"));
        assertThat(indexOf(output, "event: delta")).isLessThan(indexOf(output, "event: complete"));
        assertThat(output).contains("\"text\":\"SELECT \"", "\"text\":\"COUNT(*) FROM shop_orders\"");
        assertThat(client.schemaContext).isEqualTo(SCHEMA);
    }

    @Test
    @DisplayName("上游 error 帧转换为 error 事件且不发送 complete")
    void writeStream_shouldEmitError_whenUpstreamSendsError() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        client.lines = List.of("data: {\"error\":{\"message\":\"quota exceeded\"}}");

        String output = generate(serviceWith("test-key"));

        assertThat(output).contains("event: error", "LLM_UPSTREAM_ERROR");
        assertThat(output).doesNotContain("event: complete");
    }

    @Test
    @DisplayName("模型没有返回任何 delta 时按失败处理")
    void writeStream_shouldEmitError_whenNoDeltaArrives() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        client.lines = List.of("data: [DONE]");

        String output = generate(serviceWith("test-key"));

        assertThat(output).contains("event: error");
        assertThat(output).doesNotContain("event: complete");
    }

    @Test
    @DisplayName("未配置密钥时在读取 Schema 前失败，返回 MODEL_NOT_CONFIGURED")
    void prepare_shouldFailFast_whenApiKeyMissing() {
        SqlGenerationService service = serviceWith("");

        assertThatThrownBy(() -> service.prepare(request(), USER_ID, "req-1"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_CONFIGURED);
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                });
        assertThat(client.opened).isFalse();
        verifyNoInteractions(connectionService);
    }

    @Test
    @DisplayName("收到 complete 后把完整 SQL 标记为 COMPLETED")
    void writeStream_shouldPersistCompletedMessage() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        ConversationService.GenerationTicket ticket =
                new ConversationService.GenerationTicket(CONVERSATION_ID, MESSAGE_ID);
        when(conversationService.beginGeneration(
                USER_ID, CONVERSATION_ID, CONNECTION_ID, USER_INPUT, "req-1")).thenReturn(ticket);
        client.lines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"SELECT \"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"1\"}}]}",
                "data: [DONE]");

        SqlGenerationService service = serviceWith("test-key");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (SqlGenerationService.PreparedGeneration prepared =
                     service.prepare(conversationRequest(), USER_ID, "req-1")) {
            service.writeStream(prepared, output);
        }

        verify(conversationService).completeGeneration(ticket, "SELECT 1");
        verify(conversationService, never()).failGeneration(any(), anyString());
        verify(conversationService, never()).cancelGeneration(any(), anyString());
    }

    @Test
    @DisplayName("模型没有输出时标记 FAILED，不形成假成功记录")
    void writeStream_shouldPersistFailedMessage_whenNoDelta() throws Exception {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        ConversationService.GenerationTicket ticket =
                new ConversationService.GenerationTicket(CONVERSATION_ID, MESSAGE_ID);
        when(conversationService.beginGeneration(
                USER_ID, CONVERSATION_ID, CONNECTION_ID, USER_INPUT, "req-1")).thenReturn(ticket);
        client.lines = List.of("data: [DONE]");

        SqlGenerationService service = serviceWith("test-key");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (SqlGenerationService.PreparedGeneration prepared =
                     service.prepare(conversationRequest(), USER_ID, "req-1")) {
            service.writeStream(prepared, output);
        }

        verify(conversationService).failGeneration(ticket, "");
        verify(conversationService, never()).completeGeneration(any(), anyString());
    }

    private SqlGenerationService serviceWith(String apiKey) {
        LlmProperties properties = new LlmProperties(
                "http://127.0.0.1:9099/v1", apiKey, "test-model",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        return new SqlGenerationService(
                properties, client, objectMapper, connectionService, conversationService);
    }

    private String generate(SqlGenerationService service) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (SqlGenerationService.PreparedGeneration prepared =
                     service.prepare(request(), USER_ID, "req-1")) {
            service.writeStream(prepared, output);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static GenerateSqlRequest request() {
        return new GenerateSqlRequest(USER_INPUT, CONNECTION_ID, null, "default");
    }

    private static GenerateSqlRequest conversationRequest() {
        return new GenerateSqlRequest(USER_INPUT, CONNECTION_ID, CONVERSATION_ID, "default");
    }

    private static int indexOf(String text, String needle) {
        return text.indexOf(needle);
    }

    /** 用内存行替换真实模型，验证事件改写而不是网络行为。 */
    private static final class FakeLlmClient implements LlmClient {

        private List<String> lines = List.of();
        private String schemaContext;
        private boolean opened;

        @Override
        public LlmStream open(String userInput, String schemaContext) {
            opened = true;
            this.schemaContext = schemaContext;
            return new LlmStream(lines.stream());
        }

        @Override
        public LlmStream openChat(String systemPrompt, String userPrompt) {
            opened = true;
            return new LlmStream(lines.stream());
        }
    }
}
