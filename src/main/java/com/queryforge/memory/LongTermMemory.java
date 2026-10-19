package com.queryforge.memory;

import java.time.LocalDateTime;

/**
 * 长期记忆实体。区别于 P5 会话摘要（conversations.memory_summary），
 * 这是跨会话、有来源、有权限、可管理的长期记录。
 */
public record LongTermMemory(
        Long id,
        Long ownerId,
        Long workspaceId,
        String memoryType,   // PROFILE / TASK / FACT / EPISODIC
        String scope,        // PERSONAL / WORKSPACE
        String title,
        String content,
        String sourceRef,    // conversationId/runId 等来源引用
        String confidence,   // UNVERIFIED / VERIFIED / REJECTED
        String status,       // ACTIVE / DISABLED
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
