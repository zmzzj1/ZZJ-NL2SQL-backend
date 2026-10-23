package com.queryforge.application.agent;

import com.queryforge.domain.agent.AgentRun;

/**
 * P14-D：一次计划级审核决定的执行上下文。
 * 一次性领取（Redis 令牌 + MySQL CAS）已在 {@link AgentRunService#preparePlanDecision} 完成，
 * 进入流式执行阶段后不再重复裁决，保证同一审核版本最多一个决定生效。
 */
public record PreparedPlanDecision(
        Long userId,
        Long runId,
        AgentRun run,
        int hitlVersion,
        boolean approved,
        String feedback,
        int rejectionCount
) {
}
