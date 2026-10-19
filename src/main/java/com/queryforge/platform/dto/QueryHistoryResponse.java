package com.queryforge.platform.dto;

import com.queryforge.platform.entity.QueryStatus;
import java.time.LocalDateTime;

public record QueryHistoryResponse(
        Long id,
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
