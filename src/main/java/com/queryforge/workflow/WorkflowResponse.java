package com.queryforge.workflow;

import java.util.List;

/**
 * 工作流详情响应：定义、版本、节点运行状态。
 */
public record WorkflowResponse(
        Long id,
        String name,
        String description,
        WorkflowGraph definition,
        int revision,
        String status,
        String createdAt,
        String updatedAt
) {
}
