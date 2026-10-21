package com.queryforge.infrastructure.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Embedding 客户端：调用 OpenAI 兼容的 /embeddings 接口，返回定长向量。
 * 用于 P8 向量检索（真实语义相似度，非关键词模拟）。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EmbeddingClient(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** 单个文本嵌入。 */
    public List<Double> embed(String text) {
        List<List<Double>> batch = embedBatch(List.of(text));
        return batch.get(0);
    }

    /**
     * 批量嵌入（最多 16 条，避免单次请求过大）。
     * 返回与输入顺序一致的向量列表。
     */
    public List<List<Double>> embedBatch(List<String> texts) {
        if (!properties.configured()) {
            throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "Embedding 服务未配置");
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        if (texts.size() > 16) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "批量嵌入最多 16 条");
        }
        String body = buildBody(texts);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/embeddings"))
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("embedding_upstream_status status={} body={}",
                        response.statusCode(), truncate(response.body()));
                throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE, HttpStatus.BAD_GATEWAY,
                        "Embedding 服务暂不可用");
            }
            return parse(response.body());
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE, HttpStatus.BAD_GATEWAY, "Embedding 请求被中断", ex);
        } catch (IOException ex) {
            log.warn("embedding_connect_failed message={}", ex.getMessage());
            throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE, HttpStatus.BAD_GATEWAY, "无法连接 Embedding 服务", ex);
        }
    }

    private String buildBody(List<String> texts) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.model());
        ArrayNode input = root.putArray("input");
        for (String text : texts) {
            input.add(text);
        }
        return root.toString();
    }

    private List<List<Double>> parse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE, HttpStatus.BAD_GATEWAY,
                    "Embedding 响应格式异常");
        }
        List<List<Double>> result = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray()) {
                continue;
            }
            List<Double> vector = new ArrayList<>(embedding.size());
            for (JsonNode value : embedding) {
                vector.add(value.asDouble());
            }
            result.add(vector);
        }
        return result;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ");
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300);
    }
}
