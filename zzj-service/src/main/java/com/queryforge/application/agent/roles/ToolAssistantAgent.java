package com.queryforge.application.agent.roles;

import com.queryforge.application.agent.ToolExecutionListener;
import com.queryforge.application.mcp.McpToolExecutionCoordinator;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.agent.roles.RoleRegistry;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.application.tool.ToolRegistry;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Tool Assistant 角色（指南 §6）：受控 MCP/工具调用。
 * 复用既有 {@link ToolRegistry}（白名单 + Agent 策略过滤 + 参数校验 + 审计）与
 * {@link McpToolExecutionCoordinator}（MCP 工具定位、Schema 校验、受控调用与参数自修复）。
 *
 * <p>P14-A 双层授权语义：角色层只校验“是否允许使用工具注册表 / MCP”这一**能力名**；
 * 具体工具名交由 {@link ToolRegistry} 与当前 Run 的工具授权快照核验。
 * 不能把实际工具名硬塞进角色能力集——那会意外放开所有工具。</p>
 *
 * <p>P14-E 路由：内置工具走 {@link ToolRegistry}；已连接 MCP 服务注册的工具走
 * {@link McpToolExecutionCoordinator}（并消耗 mcp 角色能力）。两者皆不匹配则明确拒绝。</p>
 */
@Service
public class ToolAssistantAgent {

    /** 使用平台工具注册表的能力名（与 TOOL_ASSISTANT 角色契约一致）。 */
    public static final String TOOL_REGISTRY_CAPABILITY = "tool_registry";
    /** 使用 MCP 的能力名。 */
    public static final String MCP_CAPABILITY = "mcp";

    private final RoleRegistry registry;
    private final ToolRegistry toolRegistry;
    private final McpToolExecutionCoordinator coordinator;

    public ToolAssistantAgent(RoleRegistry registry, ToolRegistry toolRegistry,
                              McpToolExecutionCoordinator coordinator) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
        this.coordinator = coordinator;
    }

    public ToolInvocationResult invoke(ToolInvocation invocation, Map<String, Object> args) {
        return invoke(invocation, args, TOOL_REGISTRY_CAPABILITY, null);
    }

    public ToolInvocationResult invoke(ToolInvocation invocation, Map<String, Object> args, String capability) {
        return invoke(invocation, args, capability, null);
    }

    /**
     * 受控工具调用。角色门禁通过后再进入对应后端（ToolRegistry 或 MCP 协调器）的白名单、
     * 只读限制、输入 schema 校验与调用审计；任一环节不通过都真实拒绝。
     */
    public ToolInvocationResult invoke(ToolInvocation invocation, Map<String, Object> args,
                                       String capability, ToolExecutionListener listener) {
        if (toolRegistry.contains(invocation.toolName())) {
            registry.requireAllowed(AgentRole.TOOL_ASSISTANT,
                    capability == null ? TOOL_REGISTRY_CAPABILITY : capability);
            return toolRegistry.invoke(invocation, args);
        }
        if (coordinator.isMcpTool(invocation)) {
            registry.requireAllowed(AgentRole.TOOL_ASSISTANT, MCP_CAPABILITY);
            return coordinator.execute(invocation, args, listener);
        }
        throw new ApiException(ErrorCode.TOOL_NOT_FOUND, HttpStatus.NOT_FOUND, "工具不存在或未授权");
    }
}
