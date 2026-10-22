package com.queryforge.application.agent;

import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.Collection;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * P14-A：显式工具调用请求的服务端边界校验。
 *
 * <p>前端提交的 {@code toolInvocation} 只是“意图”，真实工具目录、输入 schema 与权限
 * 仍由服务端 {@code ToolRegistry} 裁决。本组件只负责在创建 Run 之前拒绝明显不可用的请求：
 * 工具名长度、参数是否为对象、参数键数量、嵌套深度与序列化体积。
 */
@Component
public class ToolInvocationRequestGuard {

    public static final int MAX_TOOL_NAME_CHARS = 100;
    public static final int MAX_ARGS_KEYS = 32;
    public static final int MAX_ARG_DEPTH = 4;
    public static final int MAX_ARGS_CHARS = 32 * 1024;

    /** 校验通过则原样返回；不合法抛出 400 VALIDATION_ERROR。 */
    public ToolInvocationRequest validate(ToolInvocationRequest request) {
        if (request == null) {
            return null;
        }
        String toolName = request.toolName();
        if (toolName == null || toolName.isBlank()) {
            throw invalid("toolName 不能为空");
        }
        String trimmed = toolName.trim();
        if (trimmed.length() > MAX_TOOL_NAME_CHARS) {
            throw invalid("toolName 长度超过上限 " + MAX_TOOL_NAME_CHARS);
        }
        if (!trimmed.matches("[A-Za-z0-9._:-]+")) {
            throw invalid("toolName 只能包含字母、数字与 . _ : -");
        }
        Map<String, Object> args = request.args();
        if (args == null) {
            return new ToolInvocationRequest(trimmed, Map.of());
        }
        if (args.size() > MAX_ARGS_KEYS) {
            throw invalid("工具参数个数超过上限 " + MAX_ARGS_KEYS);
        }
        int depth = depthOf(args, 1);
        if (depth > MAX_ARG_DEPTH) {
            throw invalid("工具参数嵌套深度超过上限 " + MAX_ARG_DEPTH);
        }
        int chars = serializedSize(args);
        if (chars > MAX_ARGS_CHARS) {
            throw invalid("工具参数体积超过上限 " + MAX_ARGS_CHARS + " 字符");
        }
        return new ToolInvocationRequest(trimmed, args);
    }

    private static int depthOf(Object value, int depth) {
        if (depth > MAX_ARG_DEPTH) {
            return depth;
        }
        if (value instanceof Map<?, ?> map) {
            int max = depth;
            for (Object child : map.values()) {
                max = Math.max(max, depthOf(child, depth + 1));
            }
            return max;
        }
        if (value instanceof Collection<?> collection) {
            int max = depth;
            for (Object child : collection) {
                max = Math.max(max, depthOf(child, depth + 1));
            }
            return max;
        }
        return depth;
    }

    private static int serializedSize(Map<String, Object> args) {
        int total = 0;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            total += String.valueOf(entry.getKey()).length();
            total += textSize(entry.getValue());
            if (total > MAX_ARGS_CHARS) {
                return total;
            }
        }
        return total;
    }

    private static int textSize(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof Map<?, ?> map) {
            int total = 2;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += String.valueOf(entry.getKey()).length() + textSize(entry.getValue());
            }
            return total;
        }
        if (value instanceof Collection<?> collection) {
            int total = 2;
            for (Object child : collection) {
                total += textSize(child);
            }
            return total;
        }
        return String.valueOf(value).length();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, message);
    }
}
