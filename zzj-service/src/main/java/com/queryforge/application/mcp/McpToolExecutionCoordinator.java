package com.queryforge.application.mcp;

import com.queryforge.application.agent.McpToolArgumentValidator;
import com.queryforge.application.agent.ToolArgumentRepairService;
import com.queryforge.application.agent.ToolExecutionListener;
import com.queryforge.application.agent.ToolInvocationRequestGuard;
import com.queryforge.infrastructure.persistence.ToolInvocationRepository;
import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.domain.mcp.McpRegisteredTool;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.infrastructure.mcp.McpTransport;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * MCP 工具受控执行协调器：在用户作用域唯一解析工具 → 服务端边界校验 → Schema 校验
 * （确定性参数错误进入有界 LLM 修复）→ 受控 tools/call → 审计。
 *
 * <p>仅调用前的确定性参数错误可触发修复；执行期 {@link IOException} 视为运行失败，
 * 不盲重试（避免重复副作用与掩盖真实错误）。修复循环上限 {@value #MAX_REPAIRS}，
 * 每次修复占用一次 Agent LLM 预算。</p>
 */
@Service
public class McpToolExecutionCoordinator {

    private static final int MAX_REPAIRS = 2;

    private final McpServerManager serverManager;
    private final ToolInvocationRequestGuard requestGuard;
    private final ToolArgumentRepairService repairService;
    private final ToolInvocationRepository auditRepository;

    public McpToolExecutionCoordinator(McpServerManager serverManager,
                                       ToolInvocationRequestGuard requestGuard,
                                       ToolArgumentRepairService repairService,
                                       ToolInvocationRepository auditRepository) {
        this.serverManager = serverManager;
        this.requestGuard = requestGuard;
        this.repairService = repairService;
        this.auditRepository = auditRepository;
    }

    /** 该工具是否属于当前用户已连接的 MCP 服务（供路由判断，禁止跨用户串台）。 */
    public boolean isMcpTool(ToolInvocation invocation) {
        return serverManager.findToolAnyServer(invocation.userId(), invocation.toolName()) != null;
    }

    public ToolInvocationResult execute(ToolInvocation invocation, Map<String, Object> rawArgs,
                                        ToolExecutionListener listener) {
        ToolExecutionListener observer = listener == null ? (attempt, diagnostic) -> {} : listener;
        McpRegisteredTool tool = serverManager.findToolAnyServer(invocation.userId(), invocation.toolName());
        if (tool == null) {
            throw new ApiException(ErrorCode.MCP_TOOL_CALL_FAILED, HttpStatus.NOT_FOUND,
                    "未找到已连接的 MCP 工具：" + invocation.toolName());
        }
        // 服务端边界校验（复用显式工具调用的同一套限额）
        requestGuard.validate(new ToolInvocationRequest(
                invocation.toolName(), rawArgs == null ? Map.of() : rawArgs));

        Map<String, Object> args = rawArgs == null ? Map.of() : rawArgs;
        List<String> errors = McpToolArgumentValidator.validate(tool.inputSchema(), args);
        int attempts = 0;
        while (!errors.isEmpty() && attempts < MAX_REPAIRS) {
            attempts++;
            Map<String, Object> repaired = repairService.repair(
                    invocation, tool.toolName(), tool.description(), tool.inputSchema(), args,
                    String.join("; ", errors));
            observer.onRepair(attempts, String.join("; ", errors));
            args = repaired;
            errors = McpToolArgumentValidator.validate(tool.inputSchema(), args);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, HttpStatus.BAD_REQUEST,
                    "参数修复后仍不满足工具 Schema：" + String.join("; ", errors));
        }

        McpTransport transport = serverManager.getTransport(invocation.userId(), tool.serverId());
        if (transport == null) {
            throw new ApiException(ErrorCode.MCP_TOOL_CALL_FAILED, HttpStatus.SERVICE_UNAVAILABLE,
                    "MCP 服务已断开，无法执行工具：" + invocation.toolName());
        }
        long startedAt = System.nanoTime();
        try {
            String output = transport.callTool(tool.toolName(), args);
            long durationMs = elapsedMs(startedAt);
            auditRepository.insert(invocation, "SUCCESS", durationMs, null);
            return ToolInvocationResult.text(tool.toolName(), output == null ? "" : output, durationMs);
        } catch (IOException ex) {
            long durationMs = elapsedMs(startedAt);
            auditRepository.insert(invocation, "FAILED", durationMs, ErrorCode.MCP_TOOL_CALL_FAILED.name());
            throw new ApiException(ErrorCode.MCP_TOOL_CALL_FAILED, HttpStatus.INTERNAL_SERVER_ERROR,
                    "MCP 工具调用失败", ex);
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.min((System.nanoTime() - startedAtNanos) / 1_000_000, Integer.MAX_VALUE);
    }
}
