package com.queryforge.application.agent.roles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.application.tool.ToolRegistry;
import com.queryforge.domain.agent.roles.RoleDisabledException;
import com.queryforge.domain.agent.roles.ToolNotAllowedException;
import com.queryforge.domain.agent.roles.RoleProperties;
import com.queryforge.domain.agent.roles.RoleRegistry;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P14-A T4：角色/工具双层授权。角色层只校验能力名，具体工具名由 ToolRegistry 核验，
 * 不能把实际工具名硬塞进角色能力集而意外放开所有工具。
 */
@DisplayName("Tool Assistant 双层授权")
class ToolAssistantAgentP14ATest {

    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final RoleRegistry registry = new RoleRegistry(new RoleProperties(Map.of(), Map.of()));
    private final ToolAssistantAgent agent = new ToolAssistantAgent(registry, tools);

    private final ToolInvocation invocation = new ToolInvocation(1L, "describe_schema", 7L, 5L, 11L, null, null);

    @Test
    @DisplayName("任意真实工具名都能通过角色能力门禁并委派给 ToolRegistry")
    void invoke_shouldCheckCapabilityNotToolName() {
        when(tools.invoke(any(), any())).thenReturn(new ToolInvocationResult("describe_schema", Map.of(), 1));

        agent.invoke(invocation, Map.of("table", "orders"));

        verify(tools).invoke(eq(invocation), any());
    }

    @Test
    @DisplayName("角色被禁用时真实拒绝，不进入 ToolRegistry")
    void invoke_shouldRejectWhenRoleDisabled() {
        RoleRegistry disabled = new RoleRegistry(new RoleProperties(
                Map.of(com.queryforge.domain.agent.roles.AgentRole.TOOL_ASSISTANT, false), Map.of()));
        ToolAssistantAgent agent = new ToolAssistantAgent(disabled, tools);

        assertThatThrownBy(() -> agent.invoke(invocation, Map.of()))
                .isInstanceOf(RoleDisabledException.class);
        verify(tools, org.mockito.Mockito.never()).invoke(any(), any());
    }

    @Test
    @DisplayName("显式指定 MCP 能力时按 MCP 能力名门禁")
    void invoke_shouldSupportMcpCapability() {
        when(tools.invoke(any(), any())).thenReturn(new ToolInvocationResult("mcp_tool", Map.of(), 1));

        agent.invoke(invocation, Map.of(), ToolAssistantAgent.MCP_CAPABILITY);

        verify(tools).invoke(eq(invocation), any());
    }

    @Test
    @DisplayName("未知能力名被拒绝（不放宽到全部工具）")
    void invoke_shouldRejectUnknownCapability() {
        assertThatThrownBy(() -> agent.invoke(invocation, Map.of(), "everything"))
                .isInstanceOf(ToolNotAllowedException.class);
        verify(tools, org.mockito.Mockito.never()).invoke(any(), any());
    }
}
