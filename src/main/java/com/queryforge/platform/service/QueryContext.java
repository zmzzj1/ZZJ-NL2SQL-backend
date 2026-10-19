package com.queryforge.platform.service;

/**
 * 一次 SQL 执行的归属上下文：用户、会话，以及可选的 Agent Run/Step。
 */
record QueryContext(Long userId, Long conversationId, Long runId, Long stepId) {
}
