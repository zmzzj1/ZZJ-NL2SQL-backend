package com.queryforge.conversation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 新建会话；title 为空时由服务端生成默认标题。 */
public record CreateConversationRequest(
        @NotNull(message = "connectionId 不能为空")
        Long connectionId,

        @Size(max = 120, message = "title 最长 120 字符")
        String title
) {
}
