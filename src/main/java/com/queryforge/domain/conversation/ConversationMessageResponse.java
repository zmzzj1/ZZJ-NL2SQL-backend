package com.queryforge.domain.conversation;

import com.queryforge.domain.conversation.MessageRole;
import com.queryforge.domain.conversation.MessageStatus;
import java.time.LocalDateTime;

public record ConversationMessageResponse(
        Long id,
        MessageRole role,
        String content,
        MessageStatus status,
        LocalDateTime createdAt
) {
}
