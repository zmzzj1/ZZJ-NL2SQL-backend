package com.queryforge.application.tool;

import com.queryforge.application.agent.AgentRunToolPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.tool.Tool;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.infrastructure.persistence.ToolInvocationRepository;

/**
 * 受控工具注册表：收集全部 Tool bean 形成固定白名单，服务端做参数校验与归属审计。
 * 未授权/未知工具不进入候选集、不执行。P6 默认只读，写能力一律不开放。
 * P7 增加 Agent 配置过滤：运行关联 Agent 时，工具必须在 Agent 白名单内才可执行
 * （白名单读取经 {@link AgentRunToolPolicy}，本类不直接依赖 Agent 运行仓储）。
 */
@Service
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ToolInvocationRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final AgentRunToolPolicy agentRunToolPolicy;

    public ToolRegistry(List<Tool> registeredTools, ToolInvocationRepository auditRepository,
                        ObjectMapper objectMapper, AgentRunToolPolicy agentRunToolPolicy) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.agentRunToolPolicy = agentRunToolPolicy;
        for (Tool tool : registeredTools) {
            tools.put(tool.name(), tool);
        }
    }

    /** 当前用户可见的工具目录（名称、说明、输入 schema、只读属性）。 */
    public List<ToolDescriptor> catalog() {
        return tools.values().stream()
                .map(tool -> new ToolDescriptor(tool.name(), tool.description(), tool.inputSchema(), tool.readOnly()))
                .toList();
    }

    /** 执行工具：白名单校验 → Agent 白名单过滤 → 参数校验 → 执行 → 审计。 */
    public ToolInvocationResult invoke(ToolInvocation invocation, Map<String, Object> args) {
        Tool tool = tools.get(invocation.toolName());
        if (tool == null) {
            throw new ApiException(ErrorCode.TOOL_NOT_FOUND, HttpStatus.NOT_FOUND, "工具不存在或未授权");
        }
        if (!tool.readOnly()) {
            throw new ApiException(ErrorCode.TOOL_FORBIDDEN, HttpStatus.FORBIDDEN, "该工具不支持执行");
        }
        if (!allowsByAgentPolicy(invocation)) {
            throw new ApiException(ErrorCode.TOOL_NOT_IN_AGENT_WHITELIST, HttpStatus.FORBIDDEN,
                    "当前 Agent 配置未授权该工具：" + invocation.toolName());
        }
        validateArgs(tool, args);
        long startedAt = System.nanoTime();
        try {
            Object output = tool.execute(invocation, args);
            auditRepository.insert(invocation, "SUCCESS", elapsedMs(startedAt), null);
            return new ToolInvocationResult(invocation.toolName(), output, elapsedMs(startedAt));
        } catch (ApiException ex) {
            auditRepository.insert(invocation, "FAILED", elapsedMs(startedAt), ex.getErrorCode().name());
            throw ex;
        } catch (RuntimeException ex) {
            auditRepository.insert(invocation, "FAILED", elapsedMs(startedAt), "TOOL_EXECUTION_ERROR");
            throw new ApiException(ErrorCode.TOOL_EXECUTION_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "工具执行失败");
        }
    }

    /** 运行关联 Agent 时，工具必须在 Agent 白名单内；无 Agent 或白名单为空则放行（默认行为）。 */
    private boolean allowsByAgentPolicy(ToolInvocation invocation) {
        if (invocation.agentRunId() == null) {
            return true;
        }
        List<String> allowed = agentRunToolPolicy.allowedToolNames(invocation.agentRunId());
        return allowed.isEmpty() || allowed.contains(invocation.toolName());
    }

    private void validateArgs(Tool tool, Map<String, Object> args) {
        Object required = tool.inputSchema().get("required");
        if (required instanceof List<?> requiredList) {
            for (Object name : requiredList) {
                if (args == null || !args.containsKey(name)) {
                    throw new ApiException(
                            ErrorCode.TOOL_INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, "缺少参数：" + name);
                }
            }
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.min((System.nanoTime() - startedAtNanos) / 1_000_000, Integer.MAX_VALUE);
    }

    public record ToolDescriptor(String name, String description, Map<String, Object> inputSchema, boolean readOnly) {
    }
}
