package com.queryforge.domain.agent;

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
        String userInput,

        /** 可选：指定 Agent 配置（发布版优先）。缺省沿用内置默认行为。 */
        Long agentId,

        /** 可选：指定发布版本（用于追溯与回滚后固定版本）。缺省使用 Agent 当前发布版。 */
        Long agentVersionId,

        /** 可选：true 表示读取草稿快照（Studio 测试预览）；缺省 false 使用发布版。 */
        Boolean draft,

        /** 可选：当前工作空间，命中共享知识与工作空间记忆时生效（需成员资格）。 */
        Long workspaceId
) {
}
