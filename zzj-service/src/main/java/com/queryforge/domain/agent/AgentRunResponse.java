package com.queryforge.domain.agent;

import com.queryforge.domain.agent.RunStatus;
import java.time.LocalDateTime;
import java.util.List;

public record AgentRunResponse(
        Long id,
        Long conversationId,
        Long connectionId,
        String connectionName,
        String question,
        RunStatus status,
        String route,
        List<RoleActivity> roleActivities,
        List<KnowledgeSourceRef> knowledgeSources,
        int llmCallCount,
        String summary,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<AgentStepResponse> steps,
        String graphState
) {
    public record RoleActivity(String role, String status) {
    }

    public record KnowledgeSourceRef(Long id, String sourceType, String title, int version) {
    }
}
