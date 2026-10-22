package com.queryforge.shared;

/**
 * 一次 SQL 执行的归属上下文：用户、会话，以及可选的 Agent Run/Step。
 * 置于 shared，供 agent 与 query 模块共享，避免 query 反向依赖 agent。
 */
public record QueryContext(Long userId, Long conversationId, Long runId, Long stepId) {
}
