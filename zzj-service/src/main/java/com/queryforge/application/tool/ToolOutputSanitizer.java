package com.queryforge.application.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.tool.ToolInvocationResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * P14-A：工具输出摘要。工具返回一律视为不可信数据：只做脱敏与截断后作为
 * 步骤结果摘要/SSE 摘要，绝不作为高优先级指令注入，也不回传完整原文。
 */
@Component
public class ToolOutputSanitizer {

    private static final int MAX_SUMMARY_CHARS = 800;
    private static final String EMPTY_SUMMARY = "工具未返回结果";
    /** 序列化后等价于“没有内容”的占位，不得冒充真实工具输出。 */
    private static final List<String> EMPTY_MARKERS = List.of("{}", "[]", "null", "\"\"");
    private static final List<String> SENSITIVE_KEY_MARKERS = List.of(
            "password", "passwd", "secret", "token", "apikey", "api_key", "access_key",
            "accesskey", "credential", "private_key", "privatekey");

    private final ObjectMapper objectMapper;

    public ToolOutputSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 生成受限摘要：敏感字段掩码、JSON 序列化、超长截断。 */
    public String summarize(ToolInvocationResult result) {
        if (result == null) {
            return EMPTY_SUMMARY;
        }
        Object output = result.output();
        Object safe = sanitizeValue(output);
        String text;
        try {
            text = objectMapper.writeValueAsString(safe);
        } catch (Exception ex) {
            text = String.valueOf(safe);
        }
        if (text == null || text.isBlank() || EMPTY_MARKERS.contains(text.trim())) {
            text = EMPTY_SUMMARY;
        }
        text = text.replace('\n', ' ').trim();
        return text.length() > MAX_SUMMARY_CHARS ? text.substring(0, MAX_SUMMARY_CHARS) + "…（已截断）" : text;
    }

    /**
     * 递归复制不可信输出并掩码敏感字段。工具结果经常包含嵌套对象或对象数组，
     * 只处理顶层会让深层 token/password 经 SSE 与持久化摘要泄漏。
     */
    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return mask(map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object child : collection) {
                copy.add(sanitizeValue(child));
            }
            return copy;
        }
        if (value instanceof Object[] array) {
            List<Object> copy = new ArrayList<>(array.length);
            for (Object child : array) {
                copy.add(sanitizeValue(child));
            }
            return copy;
        }
        return value;
    }

    private static Object mask(Map<?, ?> source) {
        Map<Object, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            copy.put(key, isSensitiveKey(String.valueOf(key)) ? "***" : sanitizeValue(value));
        }
        return copy;
    }

    private static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase();
        return SENSITIVE_KEY_MARKERS.stream().anyMatch(lower::contains);
    }
}
