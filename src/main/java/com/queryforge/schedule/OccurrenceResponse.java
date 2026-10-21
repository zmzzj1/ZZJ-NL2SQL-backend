package com.queryforge.schedule;

/**
 * 触发记录响应。时间统一 ISO-8601 UTC。
 */
public record OccurrenceResponse(
        Long id,
        Long scheduleId,
        String fireAt,
        String fireAtLocal,
        int workflowRevision,
        String state,
        int attempt,
        String triggerSource,
        Long workflowRunId,
        String errorCode,
        String errorMessage,
        String createdAt,
        String updatedAt
) {
}
