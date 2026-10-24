package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;
import com.queryforge.application.agent.sql.SqlCandidateSelectionService;
import com.queryforge.application.agent.sql.SqlSelection;
import com.queryforge.application.agent.sql.SqlSelectionRequest;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.query.SqlExecutionException;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * P14-ARCH-01：DataScientist Worker —— SQL 生成/选优 → 只读 Guard → 真实 JDBC 执行 → 落库。
 *
 * <p>复用 P14-B 多候选选优（{@link SqlCandidateSelectionService}）与 P3 只读执行链
 * （{@link ReadOnlySqlGuard} + {@link SqlExecutionService}）：选中 SQL 必须再过一次同一个
 * 严格 Guard 才执行；Guard 拒绝时 JDBC 零调用。执行失败按既有语义做有界 SQL 修复
 * （{@code max-repairs}，每次修复重新过 Guard 且计入 Run 预算）。
 *
 * <p>与旧链路的区别：本 Worker 不停留 WAITING_APPROVAL，合法只读 SQL 自动执行一次
 * （计划级 HITL 已在 Graph 前置把关；逐条 SQL 重复人工审批不属于六 Agent 主链，
 * 旧 {@code /steps/{id}/approve/stream} 兼容接口对旧 Run 仍然有效）。
 */
public class DataScientistWorkerAction implements WorkerAction {

    private static final Logger log = LoggerFactory.getLogger(DataScientistWorkerAction.class);

    private final SqlCandidateSelectionService selectionService;
    private final ReadOnlySqlGuard guard;
    private final SqlExecutionService executionService;
    private final AgentRunRepository repository;
    private final AgentSqlAssistant sqlAssistant;
    private final AgentLlmCallLimiter llmLimiter;
    private final int maxRepairs;

    public DataScientistWorkerAction(SqlCandidateSelectionService selectionService, ReadOnlySqlGuard guard,
                                     SqlExecutionService executionService, AgentRunRepository repository,
                                     AgentSqlAssistant sqlAssistant, AgentLlmCallLimiter llmLimiter,
                                     int maxRepairs) {
        this.selectionService = selectionService;
        this.guard = guard;
        this.executionService = executionService;
        this.repository = repository;
        this.sqlAssistant = sqlAssistant;
        this.llmLimiter = llmLimiter;
        this.maxRepairs = maxRepairs;
    }

    @Override
    public String role() {
        return "DATA_SCIENTIST";
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
        emit(writer, "step.started", new AgentStreamEvents.StepStarted(stepId, task.step().sequence()));
        emit(writer, "agent.started",
                new AgentStreamEvents.AgentStarted(runId, "DATA_SCIENTIST", "step-" + task.step().sequence()));

        SqlSelection selection;
        String sql;
        try {
            selection = selectionService.generateAndSelect(new SqlSelectionRequest(
                    runId, stepId, task.route(), ctx.question(), task.step().objective(),
                    ctx.schemaSummary(), ctx.database(), task.previousSummaries(),
                    ctx.effectiveContext() == null ? "" : nullToEmpty(ctx.effectiveContext().knowledge()),
                    ctx.effectiveContextText(),
                    ctx.effectiveContext() == null ? "" : nullToEmpty(ctx.effectiveContext().skillGuidance()),
                    ctx.config() == null ? "" : nullToEmpty(ctx.config().systemPrompt()),
                    "MySQL 8"));
            sql = guard.validate(selection.selectedSql(), ctx.database());
        } catch (ApiException ex) {
            repository.failStep(stepId, ex.getErrorCode().name(), safe(ex.getMessage()));
            return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                    ex.getErrorCode().name(), safe(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("data_scientist_generate_failed runId={} stepId={} message={}", runId, stepId, ex.getMessage());
            repository.failStep(stepId, ErrorCode.AGENT_STEP_FAILED.name(), "SQL 生成失败");
            return WorkerOutcome.failure(role(), stepId, task.attemptId(), true,
                    ErrorCode.AGENT_STEP_FAILED.name(), "SQL 生成失败");
        }
        if (sql == null || sql.isBlank()) {
            repository.failStep(stepId, ErrorCode.AGENT_PLAN_INVALID.name(), "模型未生成可执行的 SQL");
            return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                    ErrorCode.AGENT_PLAN_INVALID.name(), "模型未生成可执行的 SQL");
        }
        if (selection.multiCandidate()) {
            emit(writer, "step.sql_candidates_evaluated", new AgentStreamEvents.SqlCandidatesEvaluated(
                    stepId, selection.mode(), selection.totalCandidates(), selection.validCandidates(),
                    selection.selectedCandidateIndex(), selection.ruleScore(), selection.llmScore(),
                    selection.compositeScore(), selection.ruleFallback()));
        }
        repository.setStepSql(stepId, sql, 1, 0);

        // Act：真实只读执行 + 有界修复（修复 SQL 重新过 Guard，修复调用计入 Run 预算）。
        int repairs = 0;
        while (true) {
            QueryResultResponse result;
            try {
                result = executionService.execute(ctx.userId(),
                        new ExecuteSqlRequest(ctx.connectionId(), ctx.conversationId(), sql),
                        runId, stepId);
            } catch (SqlExecutionException ex) {
                if (!ex.isFixable() || repairs >= maxRepairs) {
                    repository.failStep(stepId, ex.getErrorCode().name(), safe(ex.getMessage()));
                    return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                            ex.getErrorCode().name(), safe(ex.getMessage()));
                }
                repairs++;
                sink.accept("sql-repair", ex.getErrorCode().name());
                final String currentSql = sql;
                String fixed;
                try {
                    fixed = guard.validate(llmLimiter.count(runId, () -> sqlAssistant.fixSql(
                            currentSql, ex.getMessage(), task.step().objective(), ctx.schemaSummary())),
                            ctx.database());
                } catch (RuntimeException fixError) {
                    String code = fixError instanceof ApiException api
                            ? api.getErrorCode().name() : ErrorCode.AGENT_STEP_FAILED.name();
                    repository.failStep(stepId, code, "SQL 自动修复失败");
                    return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                            code, "SQL 自动修复失败");
                }
                if (fixed == null || fixed.isBlank()) {
                    repository.failStep(stepId, ErrorCode.AGENT_PLAN_INVALID.name(), "修复后的 SQL 为空");
                    return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                            ErrorCode.AGENT_PLAN_INVALID.name(), "修复后的 SQL 为空");
                }
                sql = fixed;
                repository.setStepSql(stepId, sql, repairs + 1, repairs);
                continue;
            }
            String digest = com.queryforge.application.agent.AgentStepEngine.digest(result);
            int completed = repository.completeStep(stepId, result.rowCount(), result.truncated(), digest);
            if (completed == 0) {
                // 运行已被取消/终结：迟到结果不覆写终态，也不冒充成功。
                return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                        ErrorCode.AGENT_STATE_CONFLICT.name(), "运行已结束，SQL 结果未接入步骤");
            }
            // 同一次流内的真实行数据交接给下游 Code/Report 步骤（不重复 JDBC 查询）。
            ctx.stepResults().put(stepId, result);
            emit(writer, "step.succeeded",
                    new AgentStreamEvents.StepSucceeded(stepId, result.rowCount(), result.truncated(), digest));
            emit(writer, "agent.completed",
                    new AgentStreamEvents.AgentCompleted(runId, "DATA_SCIENTIST",
                            "step-" + task.step().sequence(), "SUCCESS"));
            return WorkerOutcome.success(role(), stepId, task.attemptId(), stepId, digest);
        }
    }

    /** 步骤已被领取（SSE 重连/重复派发）：按 MySQL 事实幂等重放，不二次执行 SQL。 */
    private WorkerOutcome replayOutcome(WorkerTask task, AgentRunTaskContext ctx) {
        Optional<AgentStep> existing = repository.findStep(ctx.runId(), task.stepId());
        if (existing.isPresent() && existing.get().status() == StepStatus.SUCCEEDED) {
            String summary = existing.get().resultSummary() == null ? "" : existing.get().resultSummary();
            return WorkerOutcome.success(role(), task.stepId(), task.attemptId(), task.stepId(), summary);
        }
        return WorkerOutcome.failure(role(), task.stepId(), task.attemptId(), false,
                ErrorCode.AGENT_STATE_CONFLICT.name(), "步骤已被其他执行流程领取");
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
