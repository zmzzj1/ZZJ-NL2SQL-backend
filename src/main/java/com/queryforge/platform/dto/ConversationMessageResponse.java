package com.queryforge.platform.dto;

import com.queryforge.platform.entity.MessageRole;
import com.queryforge.platform.entity.MessageStatus;
import java.time.LocalDateTime;

public record ConversationMessageResponse(
        Long id,
        MessageRole role,
        String content,
        MessageStatus status,
        LocalDateTime createdAt
) {
}
