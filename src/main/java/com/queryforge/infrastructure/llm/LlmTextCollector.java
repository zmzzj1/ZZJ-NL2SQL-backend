package com.queryforge.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.util.Iterator;
import org.springframework.http.HttpStatus;
import com.queryforge.domain.model.LlmStream;

/**
 * 把上游 SSE 的增量文本聚合成完整字符串，供规划、修复与总结使用。
 * 与单步流式生成共用同一上游协议，但这里不向前端转发中间帧。
 */
public final class LlmTextCollector {

    private LlmTextCollector() {
    }

    public static String collect(LlmStream stream, ObjectMapper objectMapper) {
        StringBuilder text = new StringBuilder();
        try (stream) {
            Iterator<String> lines = stream.lineIterator();
            while (lines.hasNext()) {
                String line = lines.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).strip();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                JsonNode node = readNode(payload, objectMapper);
                if (node.hasNonNull("error")) {
                    throw new ApiException(
                            ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型服务暂不可用");
                }
                String delta = extractText(node);
                if (!delta.isEmpty()) {
                    text.append(delta);
                }
            }
        }
        return text.toString();
    }

    private static JsonNode readNode(String payload, ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(payload);
        } catch (IOException ex) {
            throw new ApiException(
                    ErrorCode.LLM_UPSTREAM_ERROR, HttpStatus.BAD_GATEWAY, "模型返回了无法解析的数据", ex);
        }
    }

    private static String extractText(JsonNode node) {
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
}
