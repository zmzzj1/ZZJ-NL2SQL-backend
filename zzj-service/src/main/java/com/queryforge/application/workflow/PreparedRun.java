package com.queryforge.application.workflow;

import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.workflow.WorkflowGraph;

/** 一次工作流运行的启动上下文（协调器与节点执行器之间传递的值对象）。 */
public record PreparedRun(
        Long runId, Long userId, Long workflowId, Long connectionId, Long conversationId,
        String userInput, WorkflowGraph graph, ResolvedAgentConfig config
) {
}
