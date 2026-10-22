package com.queryforge.domain.agent;

import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.StepStatus;

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
        String resultSummary,
        Long analysisRunId,
        Long reportId
) {
}
