package com.queryforge.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.model.ModelTransport;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * P11 修正验证：SpringAiModelTransport 作为底层传输层，把 Spring AI ChatModel 的流式响应
 * 转换为 OpenAI 兼容 SSE 行格式（供 RoutedLlmClient 消费）。它只是传输层，不含路由/治理逻辑。
 */
class SpringAiModelTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SpringAiChatClientFactory factory = new SpringAiChatClientFactory();

    @Test
    void stream_emitsOpenAiSseLines_andTerminatesWithDone() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse r1 = new ChatResponse(List.of(new Generation(new AssistantMessage("Hello "))));
        ChatResponse r2 = new ChatResponse(List.of(new Generation(new AssistantMessage("world"))));
        when(model.stream(Mockito.any(Prompt.class))).thenReturn(Flux.just(r1, r2));

        ModelTransport.Response response = transport(model).stream("http://x/v1", "key", "m", "sys", "q");
        List<String> lines = collect(response.lines());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(lines).anyMatch(l -> l.startsWith("data: ") && l.contains("\"content\":\"Hello \""));
        assertThat(lines).anyMatch(l -> l.startsWith("data: ") && l.contains("\"content\":\"world\""));
        assertThat(lines.get(lines.size() - 1)).isEqualTo("data: [DONE]");
    }

    @Test
    void stream_handlesEmptyResponseAndNullSystemPrompt() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse r = new ChatResponse(List.of(new Generation(new AssistantMessage("select 1"))));
        when(model.stream(Mockito.any(Prompt.class))).thenReturn(Flux.just(r));

        ModelTransport.Response response = transport(model).stream("http://x/v1", "key", "m", null, "q");
        List<String> lines = collect(response.lines());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(lines).anyMatch(l -> l.contains("\"content\":\"select 1\""));
        assertThat(lines.get(lines.size() - 1)).isEqualTo("data: [DONE]");
    }

    /** 用注入的 ChatModel 构造传输层（绕过工厂缓存，便于断言）。 */
    private SpringAiModelTransport transport(ChatModel model) {
        SpringAiChatClientFactory injected = new SpringAiChatClientFactory() {
            @Override
            public ChatModel chatModel(String baseUrl, String apiKey, String modelId) {
                return model;
            }
        };
        return new SpringAiModelTransport(injected, mapper);
    }

    private static List<String> collect(java.util.stream.Stream<String> lines) {
        return lines.collect(Collectors.toList());
    }
}
