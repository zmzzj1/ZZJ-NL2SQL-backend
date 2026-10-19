package com.queryforge.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** P4：在一个已授权会话与数据源上启动 Agent 运行。 */
public record CreateAgentRunRequest(
        @NotNull(message = "conversationId 不能为空")
        Long conversationId,

        @NotNull(message = "connectionId 不能为空")
        Long connectionId,

        @NotBlank(message = "userInput 不能为空")
        @Size(max = 2000, message = "userInput 最长 2000 字符")
        String userInput
) {
}
