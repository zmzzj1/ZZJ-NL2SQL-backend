package com.queryforge.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.infrastructure.llm.LlmTextCollector;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 工具参数 LLM 自动修复：仅在 {@link McpToolArgumentValidator} 判定为确定性参数错误时触发，
 * 每次修复占用一次 Agent LLM 预算；预算不足明确失败、绝不盲重试或伪造成功。
 * 是否真正满足工具 Schema 由调用方在受控循环内复核（循环上限由协调器控制）。
 */
@Service
public class ToolArgumentRepairService {

    private final LlmClient llmClient;
    private final AgentLlmCallLimiter llmLimiter;
    private final ObjectMapper objectMapper;

    public ToolArgumentRepairService(LlmClient llmClient, AgentLlmCallLimiter llmLimiter,
                                     ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.llmLimiter = llmLimiter;
        this.objectMapper = objectMapper;
    }

    /**
     * 触发一次参数修复。调用前占用 LLM 预算；预算不足直接失败不进入模型调用。
     *
     * <p>本方法只保证输出是<b>单一合法 JSON object</b>；是否真正满足工具 Schema 由调用方
     * （{@link com.queryforge.application.mcp.McpToolExecutionCoordinator}）在受控循环内复核，
     * 从而让“最多两次修正”真实可达——若在此处直接判失败，则循环永远只能尝试一次。</p>
     */
    public Map<String, Object> repair(ToolInvocation invocation, String toolName, String toolDescription,
                                      Map<String, Object> schema, Map<String, Object> failedArgs,
                                      String validationError) {
        if (invocation.agentRunId() == null) {
            throw new ApiException(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, HttpStatus.BAD_REQUEST,
                    "缺少运行上下文，无法触发参数修复");
        }
        if (!llmLimiter.tryAcquire(invocation.agentRunId())) {
            throw new ApiException(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, HttpStatus.TOO_MANY_REQUESTS,
                    "本次运行的模型调用预算已耗尽，无法自动修复参数");
        }
        String systemPrompt = LlmArgumentRepairModel.systemPrompt();
        String userPrompt = LlmArgumentRepairModel.userPrompt(
                "调用工具 " + toolName, toolName, toolDescription, schema, failedArgs, validationError);
        String raw;
        try {
            raw = LlmTextCollector.collect(llmClient.openChat(systemPrompt, userPrompt), objectMapper);
        } catch (ApiException ex) {
            throw new ApiException(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, HttpStatus.BAD_GATEWAY,
                    "参数修复模型调用失败", ex);
        }
        return LlmArgumentRepairModel.parseStrict(raw, objectMapper);
    }
}
