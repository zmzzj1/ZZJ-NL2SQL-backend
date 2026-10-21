package com.queryforge.domain.agent;

import com.queryforge.domain.agent.RunStatus;
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
