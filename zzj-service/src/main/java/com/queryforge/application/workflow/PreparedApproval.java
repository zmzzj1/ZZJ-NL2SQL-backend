package com.queryforge.application.workflow;

import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.workflow.WorkflowGraph;

/** 一次工作流节点审批执行的上下文（从批准节点之后继续推进）。 */
public record PreparedApproval(
        Long runId, Long userId, Long workflowId, Long connectionId, Long conversationId,
        String nodeId, WorkflowGraph graph, ResolvedAgentConfig config
) {
}
