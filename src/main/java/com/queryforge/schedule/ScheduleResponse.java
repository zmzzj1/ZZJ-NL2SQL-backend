package com.queryforge.schedule;

/**
 * 计划详情响应。时间统一 ISO-8601 UTC（如 2026-10-26T01:00:00Z）。
 */
public record ScheduleResponse(
        Long id,
        Long workspaceId,
        Long workflowId,
        int workflowRevision,
        Long connectionId,
        String userInput,
        Long agentId,
        Long agentVersionId,
        String cron,
        String cronNormalized,
        String timezone,
        String status,
        String misfirePolicy,
        String overlapPolicy,
        String nextFireAt,
        String lastFireAt,
        int revision,
        String createdAt,
        String updatedAt
) {
}
