package com.queryforge.platform.dto;

import com.queryforge.platform.entity.ApprovalStatus;
import com.queryforge.platform.entity.StepStatus;

public record AgentStepResponse(
        Long id,
        int sequence,
        String objective,
        Integer dependsOn,
        StepStatus status,
        String sql,
        int sqlVersion,
        String agentRole,
        ApprovalStatus approvalStatus,
        int repairCount,
        Integer rowCount,
        Boolean truncated,
        String errorCode,
        String errorMessage,
        String resultSummary
) {
}
