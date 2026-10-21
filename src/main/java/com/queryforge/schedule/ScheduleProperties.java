package com.queryforge.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调度器运行参数（app.schedule.*）。轮询线程只做「领取+提交」，实际执行在独立有界执行器。
 */
@ConfigurationProperties(prefix = "app.schedule")
public record ScheduleProperties(
        int pollIntervalMs,
        int leaseSeconds,
        int maxClaimBatch,
        int catchUpWindowMinutes,
        int minIntervalMinutes,
        int executionThreads
) {
    public ScheduleProperties {
        if (pollIntervalMs <= 0) pollIntervalMs = 5000;
        if (leaseSeconds <= 0) leaseSeconds = 60;
        if (maxClaimBatch <= 0) maxClaimBatch = 10;
        if (catchUpWindowMinutes <= 0) catchUpWindowMinutes = 15;
        if (minIntervalMinutes <= 0) minIntervalMinutes = 1;
        if (executionThreads <= 0) executionThreads = 4;
    }
}
