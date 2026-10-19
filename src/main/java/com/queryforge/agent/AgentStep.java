package com.queryforge.agent;

import java.time.LocalDateTime;

/**
 * 计划中的一步。sqlVersion 在每次修复后递增；
 * 用户批准的是具体版本，不能用旧版本号执行新 SQL。
 */
public record AgentStep(
        Long id,
        Long runId,
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
