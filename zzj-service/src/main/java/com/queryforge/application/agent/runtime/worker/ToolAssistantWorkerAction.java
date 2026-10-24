package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.tool.AgentRunToolPolicy;
import com.queryforge.application.tool.ToolOutputSanitizer;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * P14-ARCH-01：ToolAssistant Worker —— 受控 MCP/内置工具调用。
 *
 * <p>工具身份与参数只来自计划步骤持久化的事实（{@code agent_steps.tool_name/tool_args}），
 * 不接受自由文字或模型回复猜测；仍走 Agent 工具白名单（{@link AgentRunToolPolicy}）+
 * ToolAssistantAgent（内置注册表 / 当前用户 MCP）+ 参数 schema 校验 + 审计。
 * 工具返回 {@code isError=true} 真实算失败，不冒充成功。
 */
public class ToolAssistantWorkerAction implements WorkerAction {

    private static final Logger log = LoggerFactory.getLogger(ToolAssistantWorkerAction.class);

    private final ToolAssistantAgent toolAssistant;
    private final AgentRunToolPolicy toolPolicy;
    private final ToolOutputSanitizer toolOutputSanitizer;
    private final AgentRunRepository repository;
    private final ObjectMapper objectMapper;

    public ToolAssistantWorkerAction(ToolAssistantAgent toolAssistant, AgentRunToolPolicy toolPolicy,
                                     ToolOutputSanitizer toolOutputSanitizer, AgentRunRepository repository,
                                     ObjectMapper objectMapper) {
        this.toolAssistant = toolAssistant;
        this.toolPolicy = toolPolicy;
        this.toolOutputSanitizer = toolOutputSanitizer;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String role() {
        return "TOOL_ASSISTANT";
    }

    @Override
    public WorkerOutcome act(WorkerTask task, AgentRunTaskContext ctx, WorkerDiagnosticSink sink) {
        Long runId = ctx.runId();
        Long stepId = task.stepId();
        String toolName = task.step() == null ? null : task.step().toolName();
        if (toolName == null || toolName.isBlank()) {
            repository.failNonSqlStep(runId, stepId, ErrorCode.TOOL_INVOCATION_INCOMPLETE.name(),
                    "计划中的工具步骤缺少可执行工具信息");
            return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                    ErrorCode.TOOL_INVOCATION_INCOMPLETE.name(), "计划中的工具步骤缺少可执行工具信息");
        }
        if (!toolPolicy.allowsStrictly(runId, toolName)) {
            repository.failNonSqlStep(runId, stepId, ErrorCode.TOOL_NOT_IN_AGENT_WHITELIST.name(),
                    "当前 Agent 配置未授权该工具：" + toolName);
            return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                    ErrorCode.TOOL_NOT_IN_AGENT_WHITELIST.name(), "当前 Agent 配置未授权该工具");
        }
        int claimed = repository.claimNonSqlStep(runId, stepId);
        if (claimed == 0) {
            return replayOutcome(task, ctx);
        }
        SseEventWriter writer = ctx.writer();
        emit(writer, "agent.started",
                new AgentStreamEvents.AgentStarted(runId, "TOOL_ASSISTANT", "step-" + task.step().sequence()));
        emit(writer, "step.started", new AgentStreamEvents.StepStarted(stepId, task.step().sequence()));

        ToolInvocation invocation = new ToolInvocation(
                ctx.userId(), toolName, ctx.conversationId(), runId, stepId, null, null);
        ToolInvocationResult result;
        try {
            result = toolAssistant.invoke(invocation, parseToolArgs(task.step().toolArgsJson()), null,
                    (attempt, diagnostic) -> emit(writer, "tool.repair",
                            new AgentStreamEvents.ToolRepair(runId, stepId, toolName, attempt, diagnostic)));
        } catch (ApiException ex) {
            return failTool(repository, writer, runId, stepId, task, toolName,
                    ex.getErrorCode().name(), safe(ex.getMessage()), false);
        } catch (RuntimeException ex) {
            log.warn("tool_execute_failed runId={} stepId={} tool={} message={}",
                    runId, stepId, toolName, ex.getMessage());
            return failTool(repository, writer, runId, stepId, task, toolName,
                    ErrorCode.TOOL_EXECUTION_ERROR.name(), "工具执行失败", true);
        }
        // MCP isError / 传输层错误已由协调器转为异常；此处再对结果输出做一次确定性错误标记检查。
        if (isErrorOutput(result)) {
            return failTool(repository, writer, runId, stepId, task, toolName,
                    ErrorCode.TOOL_EXECUTION_ERROR.name(), "工具返回错误", false);
        }
        String summary = toolOutputSanitizer.summarize(result);
        int completed = repository.completeNonSqlStep(stepId, summary);
        if (completed == 0) {
            return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                    ErrorCode.AGENT_STATE_CONFLICT.name(), "运行已结束，工具结果未接入步骤");
        }
        emit(writer, "agent.completed",
                new AgentStreamEvents.AgentCompleted(runId, "TOOL_ASSISTANT",
                        "step-" + task.step().sequence(), "SUCCESS"));
        emit(writer, "step.succeeded", new AgentStreamEvents.StepSucceeded(stepId, 0, false, summary));
        emit(writer, "tool.result",
                new AgentStreamEvents.ToolResult(runId, stepId, toolName, "SUCCEEDED", summary));
        return WorkerOutcome.success(role(), stepId, task.attemptId(), stepId, summary);
    }

    private WorkerOutcome replayOutcome(WorkerTask task, AgentRunTaskContext ctx) {
        Optional<AgentStep> existing = repository.findStep(ctx.runId(), task.stepId());
        if (existing.isPresent() && existing.get().status() == StepStatus.SUCCEEDED) {
            String summary = existing.get().resultSummary() == null ? "" : existing.get().resultSummary();
            return WorkerOutcome.success(role(), task.stepId(), task.attemptId(), task.stepId(), summary);
        }
        return WorkerOutcome.failure(role(), task.stepId(), task.attemptId(), false,
                ErrorCode.AGENT_STATE_CONFLICT.name(), "步骤已被其他执行流程领取");
    }

    private static WorkerOutcome failTool(AgentRunRepository repository, SseEventWriter writer, Long runId,
                                          Long stepId, WorkerTask task, String toolName, String code,
                                          String message, boolean retryable) {
        repository.failNonSqlStep(runId, stepId, code, safe(message));
        emit(writer, "tool.result",
                new AgentStreamEvents.ToolResult(runId, stepId, toolName, "FAILED", safe(message)));
        emit(writer, "step.failed", new AgentStreamEvents.StepFailed(stepId, code, safe(message)));
        return WorkerOutcome.failure("TOOL_ASSISTANT", stepId, task.attemptId(), retryable, code, safe(message));
    }

    /**
     * 结果输出的确定性错误标记检查（MCP isError 经协调器转异常后仍可能以错误体返回的场景）。
     * 仅识别明确的错误标志字段，不猜测内容。
     */
    private static boolean isErrorOutput(ToolInvocationResult result) {
        if (result == null || result.output() == null) {
            return false;
        }
        if (result.output() instanceof Map<?, ?> map) {
            Object flag = map.get("isError");
            if (flag instanceof Boolean bool) {
                return bool;
            }
        }
        return false;
    }

    /** 计划内工具参数：必须是 JSON object；损坏时不传参猜测，按空对象交给 schema 校验拒绝。 */
    private Map<String, Object> parseToolArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException ex) {
            log.warn("tool_step_args_corrupt length={}", json.length());
            return Map.of();
        }
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static void emit(SseEventWriter writer, String name, Object payload) {
        if (writer == null) {
            return;
        }
        try {
            writer.event(name, payload);
        } catch (IOException ignored) {
            // SSE 为可选输出，失败不阻断业务
        }
    }
}
