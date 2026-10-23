package com.queryforge.application.agent.roles;

import static com.queryforge.application.mcp.McpTestFakes.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.agent.ToolArgumentRepairService;
import com.queryforge.application.agent.ToolInvocationRequestGuard;
import com.queryforge.application.mcp.McpServerManager;
import com.queryforge.application.mcp.McpTestFakes;
import com.queryforge.application.mcp.McpToolExecutionCoordinator;
import com.queryforge.application.tool.ToolRegistry;
import com.queryforge.domain.mcp.McpTool;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P14-E 路由：内置工具走 ToolRegistry，已连接 MCP 工具走协调器，两者皆不匹配明确拒绝。 */
class ToolAssistantAgentRoutingTest {

    private static final Long USER = 7L;

    private record Fixture(ToolAssistantAgent agent, McpTestFakes.FakeTool builtin,
                           McpTestFakes.FakeTransport transport, McpTestFakes.FakeRoles roles) {
    }

    private Fixture fixture() {
        McpTestFakes.FakeTool builtin = new McpTestFakes.FakeTool("echo");
        McpTestFakes.FakeAudit audit = new McpTestFakes.FakeAudit();
        ToolRegistry registry =
                new ToolRegistry(List.of(builtin), audit, new ObjectMapper(), new McpTestFakes.FakePolicy());

        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(
                List.of(new McpTool("weather", "查天气", Map.of("type", "object"))), false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));
        manager.connect(USER, entity(1L, USER));

        McpToolExecutionCoordinator coordinator = new McpToolExecutionCoordinator(
                manager, new ToolInvocationRequestGuard(),
                new ToolArgumentRepairService(
                        new McpTestFakes.FakeLlmClient("{}"), new McpTestFakes.FakeLimiter(), new ObjectMapper()),
                audit);

        McpTestFakes.FakeRoles roles = new McpTestFakes.FakeRoles();
        return new Fixture(new ToolAssistantAgent(roles, registry, coordinator), builtin, transport, roles);
    }

    private static ToolInvocation invocation(String toolName) {
        return new ToolInvocation(USER, toolName, null, 100L, 200L, null, null);
    }

    @Test
    void builtinToolRoutesToToolRegistry() {
        Fixture f = fixture();

        f.agent().invoke(invocation("echo"), Map.of());

        assertEquals(1, f.builtin().calls);
        assertEquals(0, f.transport().callCount);
        assertTrue(f.roles().required.contains("TOOL_ASSISTANT:tool_registry"));
    }

    @Test
    void connectedMcpToolRoutesToCoordinator() {
        Fixture f = fixture();

        f.agent().invoke(invocation("weather"), Map.of());

        assertEquals(0, f.builtin().calls);
        assertEquals(1, f.transport().callCount);
        assertTrue(f.roles().required.contains("TOOL_ASSISTANT:mcp"), "MCP 路径必须消耗 mcp 角色能力");
    }

    @Test
    void unknownToolIsRejected() {
        Fixture f = fixture();

        ApiException ex = assertThrows(ApiException.class, () -> f.agent().invoke(invocation("nope"), Map.of()));

        assertEquals(ErrorCode.TOOL_NOT_FOUND, ex.getErrorCode());
        assertEquals(0, f.builtin().calls);
        assertEquals(0, f.transport().callCount);
    }
}
