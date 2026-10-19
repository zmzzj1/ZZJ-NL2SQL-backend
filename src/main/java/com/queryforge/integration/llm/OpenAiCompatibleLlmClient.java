package com.queryforge.integration.llm;
import com.queryforge.query.generation.SqlGenerationService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queryforge.integration.llm.LlmProperties;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容的流式 Chat Completions 客户端。
 * 只负责建立上游连接与暴露原始 SSE 行；事件改写由 SqlGenerationService 完成。
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final int MAX_ERROR_BODY_CHARS = 300;

    private static final String SYSTEM_PROMPT = """
            你是 QueryForge 的 SQL 生成器，只输出一条可在 MySQL 8 执行的只读 SELECT 语句。
            硬性要求：
            1. 只输出 SQL 本身，不要 Markdown 代码围栏、解释、前后缀或分号之外的内容。
            2. 只能使用给定 Schema 中的表和列；不得查询 information_schema、mysql、performance_schema、sys。
            3. 禁止 INSERT、UPDATE、DELETE、DDL、存储过程、INTO OUTFILE、FOR UPDATE 等写操作或锁定语句。
            4. 不确定时仍输出最接近的 SELECT，不要编造不存在的表名。
            5. 默认最多返回 200 行；聚合查询直接输出聚合结果。
            """;

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 建立上游流。网络或鉴权失败会在响应开始前抛出，便于返回标准 JSON 错误。
     */
    @Override
    public LlmStream open(String userInput, String schemaContext) {
        String effectiveSchema = (schemaContext == null || schemaContext.isBlank())
                ? "（未提供可用的表结构）"
                : schemaContext;
        String userPrompt = """
                当前连接数据库的 Schema（只读）：
                %s

                用户问题：
                %s

                请只输出一条 MySQL SELECT 语句。
                """.formatted(effectiveSchema, userInput);
        return openChat(SYSTEM_PROMPT, userPrompt);
    }

    /**
     * 建立上游流。网络或鉴权失败会在响应开始前抛出，便于返回标准 JSON 错误。
     */
    @Override
    public LlmStream openChat(String systemPrompt, String userPrompt) {
        String body = buildRequestBody(systemPrompt, userPrompt);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String errorBody = readSafely(response.body());
                log.warn("llm_upstream_status status={} body={}", response.statusCode(), truncate(errorBody));
                throw new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型服务暂不可用");
            }
            return new LlmStream(response.body());
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型请求被中断", ex);
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("llm_connect_failed message={}", ex.getMessage());
            throw new ApiException(ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "无法连接模型服务", ex);
        }
    }

    private String buildRequestBody(String systemPrompt, String userPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.model());
        root.put("stream", true);
        root.put("temperature", 0);

        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return root.toString();
    }

    private static String readSafely(Stream<String> lines) {
        try (lines) {
            return lines.limit(20).collect(Collectors.joining("\n"));
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ");
        return singleLine.length() <= MAX_ERROR_BODY_CHARS ? singleLine : singleLine.substring(0, MAX_ERROR_BODY_CHARS);
    }
}
