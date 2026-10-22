package com.queryforge.application.agent.roles;

import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.application.tool.ToolRegistry;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.agent.roles.RoleRegistry;

/**
 * Tool Assistant 角色（指南 §6）：受控 MCP/工具调用。
 * 复用既有 {@link ToolRegistry}（白名单 + Agent 策略过滤 + 参数校验 + 审计）。
 * 本类再加角色门禁：工具不在白名单或角色被禁用时真实拒绝（P1111/P1113）。
 * 工具返回不能覆盖平台指令——审批与权限仍由下游 ToolRegistry 强制。
 */
@Service
public class ToolAssistantAgent {

    private final RoleRegistry registry;
    private final ToolRegistry toolRegistry;

    public ToolAssistantAgent(RoleRegistry registry, ToolRegistry toolRegistry) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
    }

    public ToolInvocationResult invoke(ToolInvocation invocation, Map<String, Object> args) {
        registry.requireAllowed(AgentRole.TOOL_ASSISTANT, invocation.toolName());
        return toolRegistry.invoke(invocation, args);
    }
}
