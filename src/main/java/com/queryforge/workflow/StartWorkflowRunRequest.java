package com.queryforge.workflow;

import jakarta.validation.constraints.NotNull;

/**
 * 启动工作流运行的请求。绑定授权数据源与会话；SQL 节点仍走既有审批，不自动执行。
 */
public record StartWorkflowRunRequest(
        @NotNull Long connectionId,
        Long conversationId,
        String userInput,
        /** 可选：指定已发布 Agent，SQL 节点使用其配置（工具白名单/提示词/Skill/RAG）。 */
        Long agentId,
        /** 可选：指定发布版本；缺省使用 Agent 当前发布版。 */
        Long agentVersionId
) {
}
