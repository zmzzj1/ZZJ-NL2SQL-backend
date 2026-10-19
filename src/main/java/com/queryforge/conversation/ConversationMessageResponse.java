package com.queryforge.conversation;

import com.queryforge.conversation.MessageRole;
import com.queryforge.conversation.MessageStatus;
import java.time.LocalDateTime;

public record ConversationMessageResponse(
        Long id,
        MessageRole role,
        String content,
        MessageStatus status,
        LocalDateTime createdAt
) {
}
