package com.queryforge.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.platform.config.LlmProperties;
import com.queryforge.platform.dto.GenerateSqlRequest;
import com.queryforge.platform.dto.SqlStreamEvents;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 负责把上游模型的增量文本改写成本项目约定的 start/delta/complete/error 事件。
 * 生成阶段不接触业务数据库。
 */
@Service
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

    private final LlmProperties properties;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ConnectionService connectionService;

    public SqlGenerationService(
            LlmProperties properties,
            LlmClient llmClient,
            ObjectMapper objectMapper,
            ConnectionService connectionService) {
        this.properties = properties;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.connectionService = connectionService;
    }

    /**
     * 在写入 SSE 响应之前完成配置与上游连接检查；失败时仍可返回标准 JSON 错误。
     */
    public PreparedGeneration prepare(GenerateSqlRequest request, Long userId, String requestId) {
        if (!properties.configured()) {
            throw new ApiException(
                    ErrorCode.MODEL_NOT_CONFIGURED,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "模型服务未配置：请在服务端设置 LLM_API_KEY");
        }
        String schemaContext = connectionService.schemaContext(userId, request.connectionId());
        LlmStream stream = llmClient.open(request.userInput().trim(), schemaContext);
        return new PreparedGeneration(requestId, stream);
    }

    public void writeStream(PreparedGeneration prepared, OutputStream outputStream) {
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        boolean startSent = false;
        try (prepared) {
            writer.event("start", new SqlStreamEvents.Start(prepared.requestId()));
            startSent = true;

            int deltaCount = relayDeltas(prepared, writer);
            if (deltaCount == 0) {
                throw new LlmStreamException("模型未返回任何内容");
            }
            writer.event("complete", new SqlStreamEvents.Complete(prepared.requestId()));
        } catch (IOException ex) {
            // 客户端主动取消或断开：停止读取上游，不尝试再写任何事件。
            log.info("sse_client_closed requestId={} reason={}", prepared.requestId(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("sse_stream_failed requestId={} message={}", prepared.requestId(), ex.getMessage());
            if (startSent) {
                writeErrorQuietly(writer, prepared.requestId());
            }
        }
    }

    private int relayDeltas(PreparedGeneration prepared, SseEventWriter writer) throws IOException {
        Iterator<String> lines = prepared.stream().lineIterator();
        int deltaCount = 0;
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.isEmpty() || line.startsWith(":")) {
                continue;
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).strip();
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                break;
            }

            JsonNode node = parsePayload(payload);
            if (node.hasNonNull("error")) {
                throw new LlmStreamException("上游返回 error 字段");
            }
            String text = extractDeltaText(node);
            if (!text.isEmpty()) {
                writer.event("delta", new SqlStreamEvents.Delta(text));
                deltaCount++;
            }
        }
        return deltaCount;
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException ex) {
            throw new LlmStreamException("上游事件不是合法 JSON", ex);
        }
    }

    private static String extractDeltaText(JsonNode node) {
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode first = choices.get(0);
        String text = first.path("delta").path("content").asText("");
        if (text.isEmpty()) {
            text = first.path("message").path("content").asText("");
        }
        return text;
    }

    private void writeErrorQuietly(SseEventWriter writer, String requestId) {
        try {
            writer.event("error", new SqlStreamEvents.StreamError(
                    ErrorCode.LLM_UPSTREAM_ERROR.name(),
                    "模型服务暂不可用",
                    requestId));
        } catch (IOException ignored) {
            log.debug("sse_error_frame_skipped requestId={}", requestId);
        }
    }

    /**
     * 已建立的上游流 + 请求追踪 ID。由调用方负责关闭。
     */
    public static final class PreparedGeneration implements AutoCloseable {

        private final String requestId;
        private final LlmStream stream;

        PreparedGeneration(String requestId, LlmStream stream) {
            this.requestId = requestId;
            this.stream = stream;
        }

        public String requestId() {
            return requestId;
        }

        LlmStream stream() {
            return stream;
        }

        @Override
        public void close() {
            stream.close();
        }
    }

    static final class LlmStreamException extends RuntimeException {
        LlmStreamException(String message) {
            super(message);
        }

        LlmStreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
