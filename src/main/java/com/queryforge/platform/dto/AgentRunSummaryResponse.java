package com.queryforge.platform.dto;

import com.queryforge.platform.entity.RunStatus;
import java.time.LocalDateTime;

public record AgentRunSummaryResponse(
        Long id,
        Long conversationId,
        Long connectionId,
        String connectionName,
        String question,
        RunStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
