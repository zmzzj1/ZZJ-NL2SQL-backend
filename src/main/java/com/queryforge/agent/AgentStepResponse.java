package com.queryforge.agent;

import com.queryforge.agent.ApprovalStatus;
import com.queryforge.agent.StepStatus;

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
