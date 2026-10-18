package com.queryforge.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.platform.config.LlmProperties;
import com.queryforge.platform.dto.GenerateSqlRequest;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SQL 生成 SSE 事件改写")
class SqlGenerationServiceTest {

    private static final String USER_INPUT = "统计订单数量";
    private static final String SCHEMA = "demo_orders(id, amount)";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("按 start -> delta -> complete 顺序输出且不追加多余事件")
    void writeStream_shouldEmitStartDeltasAndComplete() throws Exception {
        FakeLlmClient client = new FakeLlmClient(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"SELECT \"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"COUNT(*) FROM demo_orders\"}}]}",
                "data: [DONE]"));
        SqlGenerationService service = serviceWith(client, "test-key");

        String output = generate(service);

        assertThat(output).contains("event: start", "event: delta", "event: complete");
        assertThat(indexOf(output, "event: start")).isLessThan(indexOf(output, "event: delta"));
        assertThat(indexOf(output, "event: delta")).isLessThan(indexOf(output, "event: complete"));
        assertThat(output).contains("\"text\":\"SELECT \"", "\"text\":\"COUNT(*) FROM demo_orders\"");
    }

    @Test
    @DisplayName("上游 error 帧转换为 error 事件且不发送 complete")
    void writeStream_shouldEmitError_whenUpstreamSendsError() throws Exception {
        FakeLlmClient client = new FakeLlmClient(List.of(
                "data: {\"error\":{\"message\":\"quota exceeded\"}}"));
        SqlGenerationService service = serviceWith(client, "test-key");

        String output = generate(service);

        assertThat(output).contains("event: error", "LLM_UPSTREAM_ERROR");
        assertThat(output).doesNotContain("event: complete");
    }

    @Test
    @DisplayName("模型没有返回任何 delta 时按失败处理")
    void writeStream_shouldEmitError_whenNoDeltaArrives() throws Exception {
        FakeLlmClient client = new FakeLlmClient(List.of("data: [DONE]"));
        SqlGenerationService service = serviceWith(client, "test-key");

        String output = generate(service);

        assertThat(output).contains("event: error");
        assertThat(output).doesNotContain("event: complete");
    }

    @Test
    @DisplayName("未配置密钥时在建立流之前失败，返回 MODEL_NOT_CONFIGURED")
    void prepare_shouldFailFast_whenApiKeyMissing() {
        FakeLlmClient client = new FakeLlmClient(List.of());
        SqlGenerationService service = serviceWith(client, "");

        assertThatThrownBy(() -> service.prepare(request(), "req-1"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODEL_NOT_CONFIGURED);
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                });
        assertThat(client.opened).isFalse();
    }

    private SqlGenerationService serviceWith(LlmClient client, String apiKey) {
        LlmProperties properties = new LlmProperties(
                "http://127.0.0.1:9099/v1", apiKey, "test-model",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        return new SqlGenerationService(properties, client, objectMapper);
    }

    private String generate(SqlGenerationService service) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (SqlGenerationService.PreparedGeneration prepared = service.prepare(request(), "req-1")) {
            service.writeStream(prepared, output);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static GenerateSqlRequest request() {
        return new GenerateSqlRequest(USER_INPUT, SCHEMA, "default");
    }

    private static int indexOf(String text, String needle) {
        return text.indexOf(needle);
    }

    /** 用内存行替换真实模型，验证事件改写而不是网络行为。 */
    private static final class FakeLlmClient implements LlmClient {

        private final List<String> lines;
        private boolean opened;

        private FakeLlmClient(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public LlmStream open(String userInput, String schemaContext) {
            opened = true;
            return new LlmStream(lines.stream());
        }
    }
}
