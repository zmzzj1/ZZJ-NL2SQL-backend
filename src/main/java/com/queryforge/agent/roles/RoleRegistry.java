package com.queryforge.agent.roles;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * 角色契约注册表（指南 §6）。内置默认契约，可被 {@link RoleProperties} 覆盖。
 * 工具白名单与启用状态用于 P1113：禁用工具/角色后运行时真实拒绝。
 */
@Service
public class RoleRegistry {

    private final EnumMap<AgentRole, RoleContract> contracts = new EnumMap<>(AgentRole.class);

    public RoleRegistry(RoleProperties properties) {
        RoleProperties props = properties == null ? new RoleProperties(Map.of(), Map.of()) : properties;
        for (AgentRole role : AgentRole.values()) {
            contracts.put(role, defaultContract(role, props));
        }
    }

    private static RoleContract defaultContract(AgentRole role, RoleProperties props) {
        boolean enabled = props.enabled().getOrDefault(role, true);
        int budget = props.budgetTokens().getOrDefault(role, defaultBudget(role));
        return switch (role) {
            case MANAGER -> new RoleContract(role, enabled, Set.of(), budget, false, false, false);
            case PLANNER -> new RoleContract(role, enabled, Set.of(), budget, false, false, false);
            case DATA_ANALYST -> new RoleContract(role, enabled, Set.of("sql_guard"), budget, true, false, false);
            case CODE_ANALYST -> new RoleContract(role, enabled, Set.of("python_sandbox", "builtin_analyzer"), budget, false, true, false);
            case TOOL_ASSISTANT -> new RoleContract(role, enabled, Set.of("tool_registry", "mcp"), budget, false, true, false);
            case REPORT_ASSISTANT -> new RoleContract(role, enabled, Set.of("report_service"), budget, false, false, true);
        };
    }

    private static int defaultBudget(AgentRole role) {
        return switch (role) {
            case MANAGER -> 1500;
            case PLANNER -> 2000;
            case DATA_ANALYST -> 3000;
            case CODE_ANALYST -> 3000;
            case TOOL_ASSISTANT -> 2000;
            case REPORT_ASSISTANT -> 2500;
        };
    }

    public RoleContract contract(AgentRole role) {
        return contracts.get(role);
    }

    public boolean isEnabled(AgentRole role) {
        RoleContract c = contracts.get(role);
        return c != null && c.enabled();
    }

    /** 校验角色是否被启用且工具在白名单内；否则抛出（P1113 真实拒绝）。 */
    public void requireAllowed(AgentRole role, String toolId) {
        RoleContract c = contracts.get(role);
        if (c == null || !c.enabled()) {
            throw new RoleDisabledException(role);
        }
        if (toolId != null && !c.allowsTool(toolId)) {
            throw new ToolNotAllowedException(role, toolId);
        }
    }
}
