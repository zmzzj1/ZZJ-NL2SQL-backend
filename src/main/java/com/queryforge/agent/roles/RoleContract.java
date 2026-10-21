package com.queryforge.agent.roles;

import java.util.Set;

/**
 * 角色契约：输入能力、工具白名单、上下文预算与执行边界。
 * 工具白名单用于 P1113——禁用工具后该角色真实拒绝调用。
 */
public record RoleContract(
        AgentRole role,
        boolean enabled,
        Set<String> allowedTools,
        int contextBudgetTokens,
        boolean canExecuteSql,
        boolean canExecuteTools,
        boolean canGenerateReport
) {
    public boolean allowsTool(String toolId) {
        return enabled && allowedTools != null && allowedTools.contains(toolId);
    }
}
