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
 *
 * <p>P14-A 双层授权语义：角色层只校验“是否允许使用工具注册表 / MCP”这一**能力名**；
 * 具体工具名交由 {@link ToolRegistry} 与当前 Run 的工具授权快照核验。
 * 不能把实际工具名硬塞进角色能力集——那会意外放开所有工具。
 */
@Service
public class ToolAssistantAgent {

    /** 使用平台工具注册表的能力名（与 TOOL_ASSISTANT 角色契约一致）。 */
    public static final String TOOL_REGISTRY_CAPABILITY = "tool_registry";
    /** 使用 MCP 的能力名。 */
    public static final String MCP_CAPABILITY = "mcp";

    private final RoleRegistry registry;
    private final ToolRegistry toolRegistry;

    public ToolAssistantAgent(RoleRegistry registry, ToolRegistry toolRegistry) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
    }

    public ToolInvocationResult invoke(ToolInvocation invocation, Map<String, Object> args) {
        return invoke(invocation, args, TOOL_REGISTRY_CAPABILITY);
    }

    /**
     * 受控工具调用。角色门禁通过后才进入 ToolRegistry 的白名单、只读限制、
     * 输入 schema 校验与调用审计；任一环节不通过都真实拒绝。
     */
    public ToolInvocationResult invoke(ToolInvocation invocation, Map<String, Object> args, String capability) {
        registry.requireAllowed(AgentRole.TOOL_ASSISTANT, capability == null ? TOOL_REGISTRY_CAPABILITY : capability);
        return toolRegistry.invoke(invocation, args);
    }
}
