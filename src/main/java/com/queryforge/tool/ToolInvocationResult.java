package com.queryforge.tool;

import java.util.Map;

/**
 * 工具调用结果。output 必须是可序列化且限长的结构化数据；
 * 工具输出视为不可信数据，绝不作为高优先级指令注入。
 */
public record ToolInvocationResult(
        String toolName,
        Object output,
        long durationMs
) {
    /** 构造一个仅含字符串输出的结果。 */
    public static ToolInvocationResult text(String toolName, String text, long durationMs) {
        return new ToolInvocationResult(toolName, Map.of("result", text), durationMs);
    }
}
