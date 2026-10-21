package com.queryforge.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建定时计划请求。cron 为 5/6 段表达式，timezone 为 IANA ZoneId。
 * workflowId 必须属于当前用户；connectionId 必须属于当前用户；SQL 执行仍走人工审批。
 */
public record CreateScheduleRequest(
        Long workspaceId,
        @NotNull Long workflowId,
        @NotNull Long connectionId,
        String userInput,
        Long agentId,
        Long agentVersionId,
        @NotBlank String cron,
        @NotBlank String timezone,
        String misfirePolicy,
        String overlapPolicy
) {
}
