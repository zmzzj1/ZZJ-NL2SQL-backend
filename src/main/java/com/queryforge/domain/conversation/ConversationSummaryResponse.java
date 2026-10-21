package com.queryforge.domain.conversation;

import java.time.LocalDateTime;

/** 会话列表项；不含消息正文。 */
public record ConversationSummaryResponse(
        Long id,
        String title,
        Long connectionId,
        String connectionName,
        LocalDateTime createdAt,
        LocalDateTime lastActivityAt
) {
}
