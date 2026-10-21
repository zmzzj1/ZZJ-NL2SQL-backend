package com.queryforge.agent.roles;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 角色开关与上下文预算（可选覆盖默认值）。示例配置：
 * <pre>
 * app.agent.roles.enabled.code-analyst=false
 * app.agent.roles.budget-tokens.data-analyst=4000
 * </pre>
 */
@ConfigurationProperties(prefix = "app.agent.roles")
public record RoleProperties(
        Map<AgentRole, Boolean> enabled,
        Map<AgentRole, Integer> budgetTokens
) {
    public RoleProperties {
        enabled = enabled == null ? Map.of() : enabled;
        budgetTokens = budgetTokens == null ? Map.of() : budgetTokens;
    }
}
