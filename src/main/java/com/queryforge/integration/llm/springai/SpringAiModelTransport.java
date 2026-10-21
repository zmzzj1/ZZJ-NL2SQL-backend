package com.queryforge.integration.llm.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queryforge.integration.llm.ModelTransport;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * P11 修正：Spring AI 作为底层模型调用传输层（可选执行引擎）。
 *
 * <p>本类把 Spring AI {@link ChatModel} 的流式响应转换为现有 {@code LlmStream} 兼容的
 * OpenAI SSE 行格式，仅供 {@code RoutedLlmClient} 在每次尝试时调用。路由、授权、配额、
 * 审计、熔断与故障转移仍在 {@code RoutedLlmClient} 中，本类**绝不替换或绕过**它。
 *
 * <p>装配：仅当 {@code app.llm.use-spring-ai=true} 时启用，与默认 {@code HttpModelTransport} 互斥。
 */
@Component
@ConditionalOnProperty(name = "app.llm.use-spring-ai", havingValue = "true")
public class SpringAiModelTransport implements ModelTransport {

    private final SpringAiChatClientFactory factory;
    private final ObjectMapper objectMapper;

    public SpringAiModelTransport(SpringAiChatClientFactory factory, ObjectMapper objectMapper) {
        this.factory = factory;
        this.objectMapper = objectMapper;
    }

    @Override
    public Response stream(String baseUrl, String apiKey, String model, String systemPrompt, String userPrompt) {
        ChatModel chatModel = factory.chatModel(baseUrl, apiKey, model);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt == null ? "" : systemPrompt),
                new UserMessage(userPrompt == null ? "" : userPrompt)));
        Flux<ChatResponse> flux = chatModel.stream(prompt);
        Stream<String> lines = Stream.concat(flux.map(this::toSseLine).toStream(), Stream.of("data: [DONE]"));
        return new Response(200, lines);
    }

    private String toSseLine(ChatResponse response) {
        String text = extractText(response);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        ObjectNode first = choices.addObject();
        ObjectNode delta = first.putObject("delta");
        delta.put("content", text == null ? "" : text);
        return "data: " + root.toString();
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText() == null ? "" : response.getResult().getOutput().getText();
    }
}
