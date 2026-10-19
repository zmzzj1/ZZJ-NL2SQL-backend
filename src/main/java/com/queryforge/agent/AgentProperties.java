package com.queryforge.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 的硬性预算：步骤数、修复次数与计划输入长度都必须有上限。
 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(int maxSteps, int maxRepairs, int maxPlanChars, int maxLlmCalls) {

    public AgentProperties {
        maxSteps = maxSteps <= 0 ? 3 : Math.min(maxSteps, 5);
        maxRepairs = maxRepairs < 0 ? 2 : Math.min(maxRepairs, 3);
        maxPlanChars = maxPlanChars <= 0 ? 6000 : maxPlanChars;
        maxLlmCalls = maxLlmCalls <= 0 ? 12 : maxLlmCalls;
    }
}
