package com.queryforge.tool;

/**
 * 一次工具调用的归属上下文。userId 由认证主体注入，不来自请求体。
 * 其余字段可为 null（例如在普通 Dashboard 而非 Agent Run 内调用）。
 */
public record ToolInvocation(
        Long userId,
        String toolName,
        Long conversationId,
        Long agentRunId,
        Long agentStepId,
        Long workflowRunId,
        Long workflowNodeId
) {
}
