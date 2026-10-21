package com.queryforge.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.queryforge.domain.model.ModelTransport.Response;
import com.queryforge.domain.model.ModelTransport;

/**
 * 默认底层模型传输：用 {@code java.net.http.HttpClient} 直接调用 OpenAI 兼容的
 * {@code /chat/completions}（SSE）。与 {@code SpringAiModelTransport} 互斥装配。
 *
 * <p>仅做 I/O 与消息体构建；路由/配额/审计/熔断/故障转移仍由 RoutedLlmClient 负责。
 */
@Component
@ConditionalOnProperty(name = "app.llm.use-spring-ai", havingValue = "false", matchIfMissing = true)
public class HttpModelTransport implements ModelTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpModelTransport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public Response stream(String baseUrl, String apiKey, String model, String systemPrompt, String userPrompt)
            throws IOException, InterruptedException {
        String body = buildBody(model, systemPrompt, userPrompt);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        return new Response(response.statusCode(), response.body());
    }

    private String buildBody(String modelId, String systemPrompt, String userPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelId);
        root.put("stream", true);
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt == null ? "" : systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return root.toString();
    }
}
