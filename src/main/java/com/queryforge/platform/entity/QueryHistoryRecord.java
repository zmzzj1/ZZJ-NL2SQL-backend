package com.queryforge.platform.entity;

import java.time.LocalDateTime;

/**
 * 一次真实 SQL 执行记录。不保存结果行；错误只保留分类与脱敏摘要。
 */
public record QueryHistoryRecord(
        Long id,
        Long userId,
        Long runId,
        Long stepId,
        Long conversationId,
        Long connectionId,
        String connectionName,
        String sql,
        QueryStatus status,
        LocalDateTime executedAt,
        int durationMs,
        Integer rowCount,
        Boolean truncated,
        String errorCode,
        String errorMessage
) {
}
