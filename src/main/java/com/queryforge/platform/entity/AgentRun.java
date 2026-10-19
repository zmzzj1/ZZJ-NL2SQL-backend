package com.queryforge.platform.entity;

import java.time.LocalDateTime;

/** 一次 SQL Agent 运行；状态由服务端维护，前端只展示。 */
public record AgentRun(
        Long id,
        Long userId,
        Long conversationId,
        Long connectionId,
        String connectionName,
        String question,
        RunStatus status,
        String route,
        String roleActivities,
        String knowledgeSources,
        int llmCallCount,
        String summary,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
