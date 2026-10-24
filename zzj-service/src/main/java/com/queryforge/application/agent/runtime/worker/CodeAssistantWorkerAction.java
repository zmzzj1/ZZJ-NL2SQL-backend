package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentPythonAssistant;
import com.queryforge.application.agent.AuthorizedSqlInput;
import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;
import com.queryforge.application.agent.roles.CodeAnalystAgent;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * P14-ARCH-01：CodeAssistant Worker —— 已授权上游结果 → 受控 Python → Docker 沙箱 → 工件落库。
 *
 * <p>输入只允许两种可信来源：① 同一次流内 DataScientist 真实执行交接的行数据
 * （{@link AgentRunTaskContext#stepResults()}，不重复 JDBC 查询）；② 跨请求恢复时按上游
 * 已成功步骤的原样 SQL 经 {@link SqlExecutionService} 重新鉴权取数（{@code refetched=true}）。
 * 两者都缺失即明确失败，绝不用空数组冒充数据。生成重试有界且每次计入 Run 预算。
 */
public class CodeAssistantWorkerAction implements WorkerAction {

    private static final Logger log = LoggerFactory.getLogger(CodeAssistantWorkerAction.class);
    private static final int MAX_CODE_ATTEMPTS = 3;
    private static final int MAX_COLUMN_CHARS = 4000;

    private final AgentPythonAssistant pythonAssistant;
    private final CodeAnalystAgent codeAnalyst;
    private final SqlExecutionService executionService;
    private final AgentRunRepository repository;
    private final AgentLlmCallLimiter llmLimiter;
    private final int maxCodeAttempts;

    public CodeAssistantWorkerAction(AgentPythonAssistant pythonAssistant, CodeAnalystAgent codeAnalyst,
                                     SqlExecutionService executionService, AgentRunRepository repository,
                                     AgentLlmCallLimiter llmLimiter, int maxCodeAttempts) {
        this.pythonAssistant = pythonAssistant;
        this.codeAnalyst = codeAnalyst;
        this.executionService = executionService;
        this.repository = repository;
        this.llmLimiter = llmLimiter;
        this.maxCodeAttempts = Math.max(1, maxCodeAttempts);
    }

    @Override
    public String role() {
        return "CODE_ASSISTANT";
    }

    @Override
    public WorkerOutcome act(WorkerTask task, AgentRunTaskContext ctx, WorkerDiagnosticSink sink) {
        Long runId = ctx.runId();
        Long stepId = task.stepId();
        int claimed = repository.claimNonSqlStep(runId, stepId);
        if (claimed == 0) {
            return replayOutcome(task, ctx);
        }
        SseEventWriter writer = ctx.writer();
        emit(writer, "agent.started",
                new AgentStreamEvents.AgentStarted(runId, "CODE_ASSISTANT", "step-" + task.step().sequence()));
        emit(writer, "step.started", new AgentStreamEvents.StepStarted(stepId, task.step().sequence()));

        AuthorizedSqlInput input;
        try {
            input = resolveAuthorizedInput(task, ctx);
        } catch (ApiException ex) {
            return fail(repository, writer, runId, stepId, task, ex.getErrorCode().name(), safe(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("code_input_failed runId={} stepId={} message={}", runId, stepId, ex.getMessage());
            return fail(repository, writer, runId, stepId, task, ErrorCode.AGENT_STEP_FAILED.name(),
                    "上游数据获取失败，无法开始分析");
        }
        if (input == null || input.result() == null) {
            return fail(repository, writer, runId, stepId, task, ErrorCode.AGENT_STEP_FAILED.name(),
                    "没有可证实的已授权上游结果，拒绝执行分析");
        }
        emit(writer, "analysis.started",
                new AgentStreamEvents.AnalysisStarted(runId, stepId, "CODE_ASSISTANT"));

        String code = generateWithRetry(runId, stepId, task, input, ctx, sink);
        if (code == null) {
            return fail(repository, writer, runId, stepId, task, ErrorCode.AGENT_STEP_FAILED.name(),
                    "模型未生成可用的 Python 代码");
        }

        AnalysisRunResponse analysis;
        try {
            analysis = codeAnalyst.analyzeApprovedResult(ctx.userId(), input, code, null);
        } catch (ApiException ex) {
            emit(writer, "analysis.failed", new AgentStreamEvents.AnalysisFailed(
                    runId, stepId, ex.getErrorCode().name(), safe(ex.getMessage())));
            return fail(repository, writer, runId, stepId, task, ex.getErrorCode().name(), safe(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("code_execute_failed runId={} stepId={} message={}", runId, stepId, ex.getMessage());
            emit(writer, "analysis.failed", new AgentStreamEvents.AnalysisFailed(
                    runId, stepId, ErrorCode.ANALYSIS_EXECUTION_ERROR.name(), "Python 分析执行失败"));
            return fail(repository, writer, runId, stepId, task, ErrorCode.ANALYSIS_EXECUTION_ERROR.name(),
                    "Python 分析执行失败");
        }

        String summary = interpret(runId, task, input, analysis, ctx);
        int completed = repository.completeCodeStep(runId, stepId, analysis.id(), truncate(summary, 500));
        if (completed == 0) {
            emit(writer, "analysis.failed", new AgentStreamEvents.AnalysisFailed(
                    runId, stepId, ErrorCode.AGENT_STATE_CONFLICT.name(), "运行已结束，分析结果未接入步骤"));
            return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                    ErrorCode.AGENT_STATE_CONFLICT.name(), "运行已结束，分析结果未接入步骤");
        }
        emit(writer, "analysis.succeeded", new AgentStreamEvents.AnalysisSucceeded(
                runId, stepId, analysis.id(), analysis.charts().size(), truncate(summary, 300),
                analysis.durationMs()));
        emit(writer, "agent.completed",
                new AgentStreamEvents.AgentCompleted(runId, "CODE_ASSISTANT",
                        "step-" + task.step().sequence(), "SUCCESS"));
        emit(writer, "step.succeeded", new AgentStreamEvents.StepSucceeded(
                stepId, analysis.inputRowCount(), false, truncate(summary, 500)));
        return WorkerOutcome.success(role(), stepId, task.attemptId(), analysis.id(), truncate(summary, 500));
    }

    /**
     * 解析可信授权输入：优先同一次流内的真实行数据交接；否则按上游已成功步骤原样 SQL
     * 重新鉴权执行取数（refetched=true）。上游缺失/未成功 → null（调用方明确失败）。
     */
    private AuthorizedSqlInput resolveAuthorizedInput(WorkerTask task, AgentRunTaskContext ctx) {
        Long sourceStepId = task.upstreamStepId();
        if (sourceStepId == null) {
            return null;
        }
        QueryResultResponse handed = ctx.stepResults().get(sourceStepId);
        if (handed != null) {
            return new AuthorizedSqlInput(ctx.userId(), ctx.runId(), sourceStepId, 1, ctx.connectionId(),
                    ctx.conversationId(), null, null, ctx.database(), handed, false);
        }
        Optional<AgentStep> source = repository.findStep(ctx.runId(), sourceStepId);
        if (source.isEmpty() || source.get().sql() == null || source.get().sql().isBlank()
                || source.get().status() != StepStatus.SUCCEEDED) {
            return null;
        }
        QueryResultResponse refetched = executionService.execute(ctx.userId(),
                new ExecuteSqlRequest(ctx.connectionId(), ctx.conversationId(), source.get().sql()),
                ctx.runId(), sourceStepId);
        return new AuthorizedSqlInput(ctx.userId(), ctx.runId(), sourceStepId, source.get().sqlVersion(),
                ctx.connectionId(), ctx.conversationId(), null, source.get().sql(), ctx.database(),
                refetched, true);
    }

    /** 有界生成：仅“空输出/超长代码”可重试；每次模型调用都计入 Run 预算。 */
    private String generateWithRetry(Long runId, Long stepId, WorkerTask task, AuthorizedSqlInput input,
                                     AgentRunTaskContext ctx, WorkerDiagnosticSink sink) {
        String columnsInfo = columnInfo(input.result());
        String dataSummary = dataSummary(input.result());
        String instructions = input.refetched()
                ? "注意：本次输入是跨请求重新获取的较新数据，请只基于本次输入分析。"
                : "";
        for (int attempt = 1; attempt <= maxCodeAttempts; attempt++) {
            String code = llmLimiter.count(runId, () -> pythonAssistant.generateCode(
                    runId, task.step().objective(), columnsInfo, dataSummary,
                    ctx.effectiveContextText(), instructions));
            if (code != null && !code.isBlank()) {
                return code;
            }
            sink.accept("python-generate-empty", "attempt=" + attempt);
        }
        return null;
    }

    /** 解释真实分析结果；模型不可用/预算不足时回退确定性摘要，不伪装解释成功。 */
    private String interpret(Long runId, WorkerTask task, AuthorizedSqlInput input, AnalysisRunResponse analysis,
                             AgentRunTaskContext ctx) {
        String deterministic = "分析运行 #" + analysis.id() + "：引擎 " + analysis.engine()
                + "，输入 " + analysis.inputRowCount() + " 行，产出 " + analysis.charts().size() + " 个图表。"
                + (analysis.summary() == null ? "" : analysis.summary());
        String interpretation;
        try {
            interpretation = llmLimiter.tryCount(runId, () -> pythonAssistant.interpretResult(
                    runId, ctx.question(), task.step().objective(),
                    analysis.charts(), analysis.summary(), ""));
        } catch (RuntimeException ex) {
            log.warn("python_interpret_failed runId={} message={}", runId, ex.getMessage());
            interpretation = null;
        }
        return interpretation == null || interpretation.isBlank() ? deterministic : interpretation;
    }

    private static String columnInfo(QueryResultResponse result) {
        if (result.rows().isEmpty()) {
            return "（无数据行）";
        }
        StringBuilder builder = new StringBuilder();
        for (java.util.Map.Entry<String, Object> entry : result.rows().get(0).entrySet()) {
            builder.append(entry.getKey()).append(": ")
                    .append(entry.getValue() == null ? "null" : entry.getValue().getClass().getSimpleName())
                    .append("\n");
        }
        String text = builder.toString();
        return text.length() > MAX_COLUMN_CHARS ? text.substring(0, MAX_COLUMN_CHARS) : text;
    }

    private static String dataSummary(QueryResultResponse result) {
        StringBuilder builder = new StringBuilder("共 ").append(result.rowCount()).append(" 行");
        if (result.truncated()) {
            builder.append("（已截断）");
        }
        int rows = Math.min(result.rows().size(), 20);
        for (int index = 0; index < rows; index++) {
            builder.append("\n").append(result.rows().get(index));
        }
        return builder.toString();
    }

    private WorkerOutcome replayOutcome(WorkerTask task, AgentRunTaskContext ctx) {
        Optional<AgentStep> existing = repository.findStep(ctx.runId(), task.stepId());
        if (existing.isPresent() && existing.get().status() == StepStatus.SUCCEEDED) {
            String summary = existing.get().resultSummary() == null ? "" : existing.get().resultSummary();
            return WorkerOutcome.success(role(), task.stepId(), task.attemptId(),
                    existing.get().analysisRunId(), summary);
        }
        return WorkerOutcome.failure(role(), task.stepId(), task.attemptId(), false,
                ErrorCode.AGENT_STATE_CONFLICT.name(), "步骤已被其他执行流程领取");
    }

    private static WorkerOutcome fail(AgentRunRepository repository, SseEventWriter writer, Long runId,
                                      Long stepId, WorkerTask task, String code, String message) {
        repository.failNonSqlStep(runId, stepId, code, safe(message));
        emit(writer, "analysis.failed", new AgentStreamEvents.AnalysisFailed(runId, stepId, code, safe(message)));
        emit(writer, "step.failed", new AgentStreamEvents.StepFailed(stepId, code, safe(message)));
        return WorkerOutcome.failure("CODE_ASSISTANT", stepId, task.attemptId(),
                isRetryableCode(code), code, safe(message));
    }

    private static boolean isRetryableCode(String code) {
        return ErrorCode.AGENT_STEP_FAILED.name().equals(code)
                || ErrorCode.ANALYSIS_EXECUTION_ERROR.name().equals(code);
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
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
