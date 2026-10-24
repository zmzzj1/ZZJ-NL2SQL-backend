package com.queryforge.domain.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 的硬性预算：步骤数、修复次数、计划输入长度与模型调用都必须有上限。
 *
 * <p>P14-ARCH-01：新增 {@code maxWorkerAttempts}（Worker Loop 有界重试上限）与
 * {@code dispatchMode}（In-JVM 消息总线模式 OFF/BYPASS/SWITCH）。
 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(int maxSteps, int maxRepairs, int maxPlanChars, int maxLlmCalls,
                              int maxWorkerAttempts, String dispatchMode) {

    public static final String DISPATCH_OFF = "OFF";
    public static final String DISPATCH_BYPASS = "BYPASS";
    public static final String DISPATCH_SWITCH = "SWITCH";

    public AgentProperties(int maxSteps, int maxRepairs, int maxPlanChars, int maxLlmCalls) {
        this(maxSteps, maxRepairs, maxPlanChars, maxLlmCalls, 2, DISPATCH_OFF);
    }

    public AgentProperties {
        maxSteps = maxSteps <= 0 ? 3 : Math.min(maxSteps, 5);
        maxRepairs = maxRepairs < 0 ? 2 : Math.min(maxRepairs, 3);
        maxPlanChars = maxPlanChars <= 0 ? 6000 : maxPlanChars;
        maxLlmCalls = maxLlmCalls <= 0 ? 12 : maxLlmCalls;
        maxWorkerAttempts = maxWorkerAttempts <= 0 ? 2 : Math.min(maxWorkerAttempts, 5);
        if (dispatchMode == null || dispatchMode.isBlank()) {
            dispatchMode = DISPATCH_OFF;
        }
        dispatchMode = dispatchMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!DISPATCH_OFF.equals(dispatchMode) && !DISPATCH_BYPASS.equals(dispatchMode)
                && !DISPATCH_SWITCH.equals(dispatchMode)) {
            dispatchMode = DISPATCH_OFF;
        }
    }
}
