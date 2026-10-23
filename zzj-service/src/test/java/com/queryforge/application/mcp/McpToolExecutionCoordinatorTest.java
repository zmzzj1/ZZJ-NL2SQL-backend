package com.queryforge.application.mcp;

import static com.queryforge.application.mcp.McpTestFakes.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.agent.ToolArgumentRepairService;
import com.queryforge.application.agent.ToolInvocationRequestGuard;
import com.queryforge.domain.mcp.McpTool;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 受控 MCP 执行与参数自修复：合法参数只执行一次；确定性参数错误才进入有界修复；
 * 预算不足/修复无效/工具不存在一律明确失败，绝不伪造成功或无限重试。
 */
class McpToolExecutionCoordinatorTest {

    private static final String TOOL = "weather";
    private static final Long USER = 7L;
    private static final Long SERVER = 1L;
    private static final Long OTHER_USER = 8L;

    private static Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("city", Map.of("type", "string")));
        schema.put("required", List.of("city"));
        return schema;
    }

    private record Fixture(McpToolExecutionCoordinator coordinator, McpTestFakes.FakeTransport transport,
                           McpTestFakes.FakeLlmClient llm, McpTestFakes.FakeLimiter limiter,
                           McpTestFakes.FakeAudit audit, List<String> repairs) {
    }

    private Fixture fixture(String llmReply) {
        McpTestFakes.FakeTransport transport =
                new McpTestFakes.FakeTransport(List.of(new McpTool(TOOL, "查天气", schema())), false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));
        manager.connect(USER, entity(SERVER, USER));

        McpTestFakes.FakeLlmClient llm = new McpTestFakes.FakeLlmClient(llmReply);
        McpTestFakes.FakeLimiter limiter = new McpTestFakes.FakeLimiter();
        McpTestFakes.FakeAudit audit = new McpTestFakes.FakeAudit();
        ToolArgumentRepairService repair = new ToolArgumentRepairService(llm, limiter, new ObjectMapper());
        McpToolExecutionCoordinator coordinator =
                new McpToolExecutionCoordinator(manager, new ToolInvocationRequestGuard(), repair, audit);
        return new Fixture(coordinator, transport, llm, limiter, audit, new ArrayList<>());
    }

    private static ToolInvocation invocation(Long userId, String toolName) {
        return new ToolInvocation(userId, toolName, null, 100L, 200L, null, null);
    }

    @Test
    void validArgumentsExecuteOnceWithoutRepair() {
        Fixture f = fixture("{\"city\":\"Beijing\"}");

        ToolInvocationResult result = f.coordinator().execute(
                invocation(USER, TOOL), Map.of("city", "Beijing"), null);

        assertEquals(1, f.transport().callCount, "合法参数必须只执行一次");
        assertEquals("Beijing", f.transport().lastCallArgs.get("city"));
        assertEquals(0, f.llm().calls, "参数合法不得触发修复");
        assertEquals(List.of("SUCCESS"), f.audit().statuses);
        assertEquals("weather", result.toolName());
        assertTrue(String.valueOf(((Map<?, ?>) result.output()).get("result")).startsWith("ok:"));
    }

    @Test
    void deterministicArgumentErrorTriggersRepairThenReexecutes() {
        Fixture f = fixture("{\"city\":\"Beijing\"}");
        List<String> repairs = f.repairs();

        f.coordinator().execute(invocation(USER, TOOL), Map.of("city", 123),
                (attempt, diagnostic) -> repairs.add(attempt + "|" + diagnostic));

        assertEquals(1, f.llm().calls, "确定性参数错误应触发一次修复");
        assertEquals(1, repairs.size());
        assertEquals(1, f.transport().callCount, "修复成功后应只执行一次真实调用");
        assertEquals("Beijing", f.transport().lastCallArgs.get("city"), "必须使用修复后的参数执行");
        assertEquals(List.of("SUCCESS"), f.audit().statuses);
    }

    @Test
    void repairLoopIsBoundedAndFailsWithoutCallingTool() {
        Fixture f = fixture("{\"city\":123}");
        List<String> repairs = f.repairs();

        ApiException ex = assertThrows(ApiException.class, () -> f.coordinator().execute(
                invocation(USER, TOOL), Map.of("city", 123),
                (attempt, diagnostic) -> repairs.add(attempt + "|" + diagnostic)));

        assertEquals(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, ex.getErrorCode());
        assertEquals(2, f.llm().calls, "修复次数必须有上限（最多两次）");
        assertEquals(2, repairs.size());
        assertEquals(0, f.transport().callCount, "参数始终不合法时不得发起真实调用");
        assertTrue(f.audit().statuses.isEmpty());
    }

    @Test
    void exhaustedBudgetFailsWithoutModelCallOrToolCall() {
        Fixture f = fixture("{\"city\":\"Beijing\"}");
        f.limiter().allow = false;

        ApiException ex = assertThrows(ApiException.class, () -> f.coordinator().execute(
                invocation(USER, TOOL), Map.of("city", 123), null));

        assertEquals(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, ex.getErrorCode());
        assertEquals(0, f.llm().calls, "预算不足不得发起模型调用");
        assertEquals(0, f.transport().callCount);
    }

    @Test
    void unknownToolIsRejected() {
        Fixture f = fixture("{\"city\":\"Beijing\"}");

        ApiException ex = assertThrows(ApiException.class, () -> f.coordinator().execute(
                invocation(USER, "nope"), Map.of(), null));

        assertEquals(ErrorCode.MCP_TOOL_CALL_FAILED, ex.getErrorCode());
        assertEquals(0, f.transport().callCount);
    }

    @Test
    void toolOfAnotherUserIsNotResolvable() {
        Fixture f = fixture("{\"city\":\"Beijing\"}");

        ApiException ex = assertThrows(ApiException.class, () -> f.coordinator().execute(
                invocation(OTHER_USER, TOOL), Map.of("city", "Beijing"), null));

        assertEquals(ErrorCode.MCP_TOOL_CALL_FAILED, ex.getErrorCode());
        assertEquals(0, f.transport().callCount, "禁止跨用户执行工具");
    }

    @Test
    void argumentsBeyondServerSideLimitsAreRejected() {
        Fixture f = fixture("{\"city\":\"Beijing\"}");
        Map<String, Object> oversized = new LinkedHashMap<>();
        for (int i = 0; i < ToolInvocationRequestGuard.MAX_ARGS_KEYS + 1; i++) {
            oversized.put("k" + i, i);
        }

        ApiException ex = assertThrows(ApiException.class, () -> f.coordinator().execute(
                invocation(USER, TOOL), oversized, null));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertEquals(0, f.transport().callCount);
    }

    @Test
    void isMcpToolIsUserScoped() {
        Fixture f = fixture("{}");
        assertTrue(f.coordinator().isMcpTool(invocation(USER, TOOL)));
        assertTrue(!f.coordinator().isMcpTool(invocation(OTHER_USER, TOOL)));
        assertTrue(!f.coordinator().isMcpTool(invocation(USER, "nope")));
    }
}
