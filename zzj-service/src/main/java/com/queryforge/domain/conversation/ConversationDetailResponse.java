package com.queryforge.domain.conversation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话详情。messages 按时间正序返回最近的消息；
 * 超过上限时 messagesTruncated 为 true，前端提示可能不完整。
 */
public record ConversationDetailResponse(
        Long id,
        String title,
        Long connectionId,
        String connectionName,
        LocalDateTime createdAt,
        LocalDateTime lastActivityAt,
        List<ConversationMessageResponse> messages,
        boolean messagesTruncated
) {
}
