package com.queryforge.workflow;

import java.util.List;

/**
 * 工作流运行详情响应：终态、节点状态列表。
 */
public record WorkflowRunResponse(
        Long id,
        Long workflowId,
        int workflowRevision,
        String status,
        String errorCode,
        String errorMessage,
        List<NodeState> nodes,
        String createdAt,
        String updatedAt
) {
    public record NodeState(
            String nodeId,
            String nodeType,
            int sequence,
            String status,
            String outputSummary,
            String errorCode,
            String errorMessage
    ) {
    }
}
