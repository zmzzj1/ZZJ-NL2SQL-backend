package com.queryforge.platform.entity;

import java.time.LocalDateTime;

/** 会话中的一条消息；助手消息的 content 是生成的 SQL 文本。 */
public record ConversationMessage(
        Long id,
        Long conversationId,
        MessageRole role,
        String content,
        MessageStatus status,
        String requestId,
        LocalDateTime createdAt
) {
}
