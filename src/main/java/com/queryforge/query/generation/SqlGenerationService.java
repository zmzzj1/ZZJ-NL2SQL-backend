package com.queryforge.query.generation;
import com.queryforge.conversation.ConversationService;
import com.queryforge.datasource.ConnectionService;
import com.queryforge.integration.llm.LlmClient;
import com.queryforge.integration.llm.LlmStream;
import com.queryforge.query.execution.SseEventWriter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.integration.llm.LlmProperties;
import com.queryforge.query.generation.GenerateSqlRequest;
import com.queryforge.query.execution.SqlStreamEvents;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 负责把上游模型的增量文本改写成本项目约定的 start/delta/complete/error 事件。
 * 生成阶段不接触业务数据库；会话消息状态只在真实完成/失败/取消时落库。
 */
@Service
public class SqlGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerationService.class);

    private final LlmProperties properties;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ConnectionService connectionService;
    private final ConversationService conversationService;

    public SqlGenerationService(
            LlmProperties properties,
            LlmClient llmClient,
            ObjectMapper objectMapper,
            ConnectionService connectionService,
            ConversationService conversationService) {
        this.properties = properties;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.connectionService = connectionService;
        this.conversationService = conversationService;
    }

    /**
     * 在写入 SSE 响应之前完成配置、Schema 与上游连接检查；失败时仍可返回标准 JSON 错误。
     * 传入 conversationId 时，先持久化用户问题与 IN_PROGRESS 助手消息。
     */
    public PreparedGeneration prepare(GenerateSqlRequest request, Long userId, String requestId) {
        if (!properties.configured()) {
            throw new ApiException(
                    ErrorCode.MODEL_NOT_CONFIGURED,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "模型服务未配置：请在服务端设置 LLM_API_KEY");
        }
        String question = request.userInput().trim();
        String schemaContext = connectionService.schemaContext(userId, request.connectionId());
        ConversationService.GenerationTicket ticket = request.conversationId() == null
                ? null
                : conversationService.beginGeneration(
                        userId, request.conversationId(), request.connectionId(), question, requestId);

        LlmStream stream;
        try {
            // P12 S4：携带 conversationId，使路由授权与模型会话亲和性按会话维度生效。
            com.queryforge.model.ModelRouteContextHolder.set(
                    new com.queryforge.model.ModelRouteContextHolder.Context(
                            userId, null, null, null, null, request.conversationId()));
            stream = llmClient.open(question, schemaContext);
        } catch (RuntimeException ex) {
            if (ticket != null) {
                try {
                    conversationService.failGeneration(ticket, "");
                } catch (RuntimeException persistFailure) {
                    log.error("conversation_message_fail_persist_failed requestId={}",
                            requestId, persistFailure);
                }
            }
            throw ex;
        } finally {
            com.queryforge.model.ModelRouteContextHolder.clear();
        }
        return new PreparedGeneration(requestId, stream, ticket);
    }

    /** 前端取消生成时调用；与断流检测互为兜底，重复调用无副作用。 */
    public void cancel(Long userId, String requestId) {
        conversationService.cancelByRequestId(userId, requestId);
    }

    public void writeStream(PreparedGeneration prepared, OutputStream outputStream) {
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        StringBuilder sql = new StringBuilder();
        boolean startSent = false;
        try (prepared) {
            writer.event("start", new SqlStreamEvents.Start(prepared.requestId()));
            startSent = true;

            int deltaCount = relayDeltas(prepared, writer, sql);
            if (deltaCount == 0) {
                throw new LlmStreamException("模型未返回任何内容");
            }
            writer.event("complete", new SqlStreamEvents.Complete(prepared.requestId()));
            persistCompletion(prepared, sql.toString());
        } catch (IOException ex) {
            // 客户端主动取消或断开：停止读取上游，不尝试再写任何事件。
            log.info("sse_client_closed requestId={} reason={}", prepared.requestId(), ex.getMessage());
            persistCancellation(prepared, sql.toString());
        } catch (RuntimeException ex) {
            log.warn("sse_stream_failed requestId={} message={}", prepared.requestId(), ex.getMessage());
            persistFailure(prepared, sql.toString());
            if (startSent) {
                writeErrorQuietly(writer, prepared.requestId());
            }
        }
    }

    private int relayDeltas(PreparedGeneration prepared, SseEventWriter writer, StringBuilder sql)
            throws IOException {
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
                sql.append(text);
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

    private void persistCompletion(PreparedGeneration prepared, String sql) {
        if (prepared.ticket() == null) {
            return;
        }
        try {
            conversationService.completeGeneration(prepared.ticket(), sql);
        } catch (RuntimeException ex) {
            // 客户端已收到 complete，不能因为持久化失败再补发 error 事件。
            log.error("conversation_message_complete_persist_failed requestId={}",
                    prepared.requestId(), ex);
        }
    }

    private void persistCancellation(PreparedGeneration prepared, String partialSql) {
        if (prepared.ticket() == null) {
            return;
        }
        try {
            conversationService.cancelGeneration(prepared.ticket(), partialSql);
        } catch (RuntimeException ex) {
            log.error("conversation_message_cancel_persist_failed requestId={}",
                    prepared.requestId(), ex);
        }
    }

    private void persistFailure(PreparedGeneration prepared, String partialSql) {
        if (prepared.ticket() == null) {
            return;
        }
        try {
            conversationService.failGeneration(prepared.ticket(), partialSql);
        } catch (RuntimeException ex) {
            log.error("conversation_message_fail_persist_failed requestId={}",
                    prepared.requestId(), ex);
        }
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
     * 已建立的上游流 + 请求追踪 ID + 可选的会话消息。由调用方负责关闭。
     */
    public static final class PreparedGeneration implements AutoCloseable {

        private final String requestId;
        private final LlmStream stream;
        private final ConversationService.GenerationTicket ticket;

        PreparedGeneration(String requestId, LlmStream stream, ConversationService.GenerationTicket ticket) {
            this.requestId = requestId;
            this.stream = stream;
            this.ticket = ticket;
        }

        public String requestId() {
            return requestId;
        }

        LlmStream stream() {
            return stream;
        }

        ConversationService.GenerationTicket ticket() {
            return ticket;
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
