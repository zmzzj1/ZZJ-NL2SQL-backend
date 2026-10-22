package com.queryforge.domain.agent;

import java.time.LocalDateTime;

/**
 * 计划中的一步。sqlVersion 在每次修复后递增；
 * 用户批准的是具体版本，不能用旧版本号执行新 SQL。
 *
 * <p>P14-C：非 SQL 步骤（CODE_ANALYST/REPORT_ASSISTANT）不写 sql/sqlVersion，
 * 成功后把真实工件 ID 挂在 {@code analysisRunId}/{@code reportId} 上，
 * 供详情恢复与下游 Report 步骤引用。
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
        LocalDateTime updatedAt,
        Long analysisRunId,
        Long reportId
) {
}
