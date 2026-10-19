package com.queryforge.workflow;

import jakarta.validation.constraints.NotNull;

/**
 * 启动工作流运行的请求。绑定授权数据源与会话；SQL 节点仍走既有审批，不自动执行。
 */
public record StartWorkflowRunRequest(
        @NotNull Long connectionId,
        Long conversationId,
        String userInput
) {
}
