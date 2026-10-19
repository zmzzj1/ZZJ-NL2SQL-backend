package com.queryforge.conversation;

import java.time.LocalDateTime;

/**
 * 一次对话会话。connectionId 可因连接删除而变为 null，
 * connectionName 是创建时的快照，历史仍可读。
 */
public record Conversation(
        Long id,
        Long userId,
        Long connectionId,
        String connectionName,
        String title,
        LocalDateTime createdAt,
        LocalDateTime lastActivityAt
) {
}
