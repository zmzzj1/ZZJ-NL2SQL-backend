package com.queryforge.application.agent.runtime.worker;

import com.queryforge.application.agent.runtime.AgentRunFinalizer;
import com.queryforge.application.agent.runtime.AgentRunTaskContext;
import com.queryforge.application.agent.runtime.WorkerOutcome;

import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentReportComposer;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.application.agent.AuthorizedSqlInput;
import com.queryforge.application.agent.roles.CodeAnalystAgent;
import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.application.report.ReportService;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.report.ReportResponse;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * P14-ARCH-01：Dashboard Worker —— 汇总前序真实 SQL/Code/Tool 输出，生成最终答复与报告（一次完成）。
 *
 * <p>两种形态：① 计划含 REPORT_ASSISTANT 步骤时，作为该步骤领取并调用
 * {@link ReportService#createForAgentRun} 生成真实报告工件（reportId 可鉴权访问）；
 * ② 无报告步骤时，仅基于真实步骤摘要合成最终答复。两者都只产生一个终态：
 * Run=COMPLETED、会话消息与 {@code run.completed} 各一次，不与 Manager 重复生成。
 */
public class DashboardWorkerAction implements WorkerAction {

    private static final Logger log = LoggerFactory.getLogger(DashboardWorkerAction.class);

    private final ReportService reportService;
    private final AgentReportComposer reportComposer;
    private final AgentSqlAssistant sqlAssistant;
    private final AnalysisService analysisService;
    private final CodeAnalystAgent codeAnalyst;
    private final SqlExecutionService executionService;
    private final ConversationService conversationService;
    private final AgentRunRepository repository;
    private final AgentRunFinalizer finalizer;
    private final AgentLlmCallLimiter llmLimiter;

    public DashboardWorkerAction(ReportService reportService, AgentReportComposer reportComposer,
                                 AgentSqlAssistant sqlAssistant, AnalysisService analysisService,
                                 CodeAnalystAgent codeAnalyst, SqlExecutionService executionService,
                                 ConversationService conversationService, AgentRunRepository repository,
                                 AgentRunFinalizer finalizer, AgentLlmCallLimiter llmLimiter) {
        this.reportService = reportService;
        this.reportComposer = reportComposer;
        this.sqlAssistant = sqlAssistant;
        this.analysisService = analysisService;
        this.codeAnalyst = codeAnalyst;
        this.executionService = executionService;
        this.conversationService = conversationService;
        this.repository = repository;
        this.finalizer = finalizer;
        this.llmLimiter = llmLimiter;
    }

    @Override
    public String role() {
        return "DASHBOARD";
    }

    @Override
    public WorkerOutcome act(WorkerTask task, AgentRunTaskContext ctx, WorkerDiagnosticSink sink) {
        Long runId = ctx.runId();
        Long stepId = task.stepId();
        SseEventWriter writer = ctx.writer();
        List<AgentStep> steps = repository.findSteps(runId);

        // 形态①：计划含待执行报告步骤 → 领取并生成真实报告工件。
        if (stepId != null && isReportStep(task.step())) {
            int claimed = repository.claimNonSqlStep(runId, stepId);
            if (claimed == 0) {
                return replayOutcome(task, ctx);
            }
            emit(writer, "agent.started",
                    new AgentStreamEvents.AgentStarted(runId, "DASHBOARD", "step-" + task.step().sequence()));
            emit(writer, "step.started", new AgentStreamEvents.StepStarted(stepId, task.step().sequence()));
            List<Long> analysisIds = succeededAnalysisIds(steps);
            if (analysisIds.isEmpty()) {
                analysisIds = builtinAnalysisIds(ctx, steps, task);
            }
            if (analysisIds.isEmpty()) {
                return fail(repository, writer, runId, stepId, task,
                        ErrorCode.AGENT_STEP_FAILED.name(), "没有可用的真实结果，无法生成报告");
            }
            String narrative = composeNarrative(ctx, steps, analysisIds);
            ReportResponse report;
            try {
                report = reportService.createForAgentRun(ctx.userId(), runId, stepId, analysisIds,
                        reportTitle(ctx), narrative);
            } catch (ApiException ex) {
                return fail(repository, writer, runId, stepId, task,
                        ex.getErrorCode().name(), safe(ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("dashboard_report_failed runId={} stepId={} message={}", runId, stepId, ex.getMessage());
                return fail(repository, writer, runId, stepId, task,
                        ErrorCode.AGENT_STEP_FAILED.name(), "报告生成失败");
            }
            String summary = truncate("报告《" + report.title() + "》已生成（#" + report.id() + "）", 500);
            int completed = repository.completeReportStep(runId, stepId, report.id(), summary);
            if (completed == 0) {
                return WorkerOutcome.failure(role(), stepId, task.attemptId(), false,
                        ErrorCode.AGENT_STATE_CONFLICT.name(), "运行已结束，报告未接入步骤");
            }
            emit(writer, "report.ready",
                    new AgentStreamEvents.ReportReady(runId, stepId, report.id(), report.title()));
            emit(writer, "agent.completed",
                    new AgentStreamEvents.AgentCompleted(runId, "DASHBOARD",
                            "step-" + task.step().sequence(), "SUCCESS"));
            emit(writer, "step.succeeded",
                    new AgentStreamEvents.StepSucceeded(stepId, 0, false, summary));
            return completeRun(ctx, writer, summary, report.id());
        }

        // 形态②：无报告步骤 → 仅基于真实步骤摘要合成最终答复。
        emit(writer, "agent.started",
                new AgentStreamEvents.AgentStarted(runId, "DASHBOARD", "summary"));
        String answer = composeFinalAnswer(ctx, steps);
        return completeRun(ctx, writer, answer, null);
    }

    /** 终态收敛（共用 AgentRunFinalizer）：Run=COMPLETED、会话消息、run.completed 各一次。 */
    private WorkerOutcome completeRun(AgentRunTaskContext ctx, SseEventWriter writer, String answer,
                                      Long reportId) {
        String summary = finalizer.complete(ctx, writer, repository.findSteps(ctx.runId()), answer);
        emit(writer, "agent.completed",
                new AgentStreamEvents.AgentCompleted(ctx.runId(), "DASHBOARD", "summary", "SUCCESS"));
        return WorkerOutcome.success(role(), null, null, reportId, truncate(summary, 500));
    }

    private String composeFinalAnswer(AgentRunTaskContext ctx, List<AgentStep> steps) {
        String stepResults = allStepSummaries(steps);
        String answer;
        try {
            answer = llmLimiter.count(ctx.runId(), () -> sqlAssistant.summarize(ctx.question(), stepResults));
        } catch (RuntimeException ex) {
            log.warn("dashboard_summary_failed runId={} message={}", ctx.runId(), ex.getMessage());
            answer = null;
        }
        if (answer == null || answer.isBlank()) {
            answer = "查询与分析已完成，共 " + steps.size() + " 步。\n" + stepResults;
        }
        return answer;
    }

    private List<Long> succeededAnalysisIds(List<AgentStep> steps) {
        List<Long> ids = new ArrayList<>();
        for (AgentStep step : steps) {
            if ("CODE_ANALYST".equals(step.agentRole()) && step.status() == StepStatus.SUCCEEDED
                    && step.analysisRunId() != null) {
                ids.add(step.analysisRunId());
            }
        }
        return ids;
    }

    /**
     * 仅已成功 SQL、尚无分析工件时：用受信任内置分析器从真实受限数据生成 typed charts。
     * 输入只允许同一次流内交接的真实行数据或按原样 SQL 重新鉴权取数，不重新生成查询。
     */
    private List<Long> builtinAnalysisIds(AgentRunTaskContext ctx, List<AgentStep> steps, WorkerTask task) {
        Long sourceStepId = task.upstreamStepId();
        if (sourceStepId == null) {
            return List.of();
        }
        try {
            QueryResultResponse handed = ctx.stepResults().get(sourceStepId);
            AuthorizedSqlInput input;
            if (handed != null) {
                input = new AuthorizedSqlInput(ctx.userId(), ctx.runId(), sourceStepId, 1, ctx.connectionId(),
                        ctx.conversationId(), null, null, ctx.database(), handed, false);
            } else {
                Optional<AgentStep> source = repository.findStep(ctx.runId(), sourceStepId);
                if (source.isEmpty() || source.get().sql() == null || source.get().sql().isBlank()
                        || source.get().status() != StepStatus.SUCCEEDED) {
                    return List.of();
                }
                QueryResultResponse refetched = executionService.execute(ctx.userId(),
                        new ExecuteSqlRequest(ctx.connectionId(), ctx.conversationId(), source.get().sql()),
                        ctx.runId(), sourceStepId);
                input = new AuthorizedSqlInput(ctx.userId(), ctx.runId(), sourceStepId,
                        source.get().sqlVersion(), ctx.connectionId(), ctx.conversationId(), null,
                        source.get().sql(), ctx.database(), refetched, true);
            }
            AnalysisRunResponse builtin = codeAnalyst.analyzeApprovedResult(ctx.userId(), input, null, null);
            return List.of(builtin.id());
        } catch (RuntimeException ex) {
            log.warn("dashboard_builtin_analysis_failed runId={} sourceStep={} message={}",
                    ctx.runId(), sourceStepId, ex.getMessage());
            return List.of();
        }
    }

    /** 报告导语：材料全部来自真实工件；模型不可用时返回 null（报告只呈现真实工件）。 */
    private String composeNarrative(AgentRunTaskContext ctx, List<AgentStep> steps, List<Long> analysisIds) {
        StringBuilder sqlFacts = new StringBuilder();
        for (AgentStep step : steps) {
            if (step.status() == StepStatus.SUCCEEDED && step.resultSummary() != null
                    && !"CODE_ANALYST".equals(step.agentRole())
                    && !"REPORT_ASSISTANT".equals(step.agentRole())) {
                sqlFacts.append("步骤 ").append(step.sequence()).append("：")
                        .append(step.resultSummary()).append("\n");
            }
        }
        StringBuilder analysisFacts = new StringBuilder();
        StringBuilder chartDigest = new StringBuilder();
        for (Long analysisId : analysisIds) {
            AnalysisRunResponse analysis;
            try {
                analysis = analysisService.detail(ctx.userId(), analysisId);
            } catch (RuntimeException ex) {
                log.warn("dashboard_analysis_read_failed runId={} analysisId={} message={}",
                        ctx.runId(), analysisId, ex.getMessage());
                continue;
            }
            analysisFacts.append("分析运行 #").append(analysis.id()).append("：")
                    .append(analysis.summary() == null ? "" : analysis.summary()).append("\n");
            for (ChartSchema.TypedChart chart : analysis.charts()) {
                chartDigest.append(chartKind(chart)).append(" / ").append(chartTitle(chart))
                        .append(" / 来源 ").append(chartSource(chart)).append("\n");
            }
        }
        if (analysisFacts.isEmpty() && sqlFacts.toString().isBlank()) {
            return null;
        }
        try {
            return llmLimiter.tryCount(ctx.runId(), () -> reportComposer.compose(
                    ctx.runId(), ctx.question(), sqlFacts.toString(), analysisFacts.toString(),
                    chartDigest.toString()));
        } catch (RuntimeException ex) {
            log.warn("dashboard_compose_failed runId={} message={}", ctx.runId(), ex.getMessage());
            return null;
        }
    }

    private static String chartKind(ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.BarChart) {
            return "柱状图";
        }
        if (chart instanceof ChartSchema.LineChart) {
            return "折线图";
        }
        if (chart instanceof ChartSchema.PieChart) {
            return "饼图";
        }
        if (chart instanceof ChartSchema.TableChart) {
            return "表格";
        }
        return "图表";
    }

    private static String chartTitle(ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.BarChart bar) {
            return bar.title();
        }
        if (chart instanceof ChartSchema.LineChart line) {
            return line.title();
        }
        if (chart instanceof ChartSchema.PieChart pie) {
            return pie.title();
        }
        if (chart instanceof ChartSchema.TableChart table) {
            return table.title();
        }
        return "";
    }

    private static String chartSource(ChartSchema.TypedChart chart) {
        if (chart instanceof ChartSchema.BarChart bar) {
            return String.valueOf(bar.sourceRunId());
        }
        if (chart instanceof ChartSchema.LineChart line) {
            return String.valueOf(line.sourceRunId());
        }
        if (chart instanceof ChartSchema.PieChart pie) {
            return String.valueOf(pie.sourceRunId());
        }
        if (chart instanceof ChartSchema.TableChart table) {
            return String.valueOf(table.sourceRunId());
        }
        return "";
    }

    private static boolean isReportStep(com.queryforge.application.agent.runtime.GraphPlanStep step) {
        if (step == null) {
            return false;
        }
        return "REPORT_ASSISTANT".equals(step.role()) || "DASHBOARD".equals(step.role());
    }

    private static String reportTitle(AgentRunTaskContext ctx) {
        String question = ctx.question() == null ? "" : ctx.question().trim();
        return question.isEmpty() ? "Agent 数据分析报告" : truncate(question, 120);
    }

    private static String allStepSummaries(List<AgentStep> steps) {
        StringBuilder builder = new StringBuilder();
        for (AgentStep step : steps) {
            if (step.resultSummary() != null && !step.resultSummary().isBlank()) {
                builder.append("步骤 ").append(step.sequence()).append("：")
                        .append(step.resultSummary()).append("\n");
            }
        }
        return builder.toString();
    }

    private WorkerOutcome replayOutcome(WorkerTask task, AgentRunTaskContext ctx) {
        Optional<AgentStep> existing = repository.findStep(ctx.runId(), task.stepId());
        if (existing.isPresent() && existing.get().status() == StepStatus.SUCCEEDED) {
            String summary = existing.get().resultSummary() == null ? "" : existing.get().resultSummary();
            return WorkerOutcome.success(role(), task.stepId(), task.attemptId(),
                    existing.get().reportId(), summary);
        }
        return WorkerOutcome.failure(role(), task.stepId(), task.attemptId(), false,
                ErrorCode.AGENT_STATE_CONFLICT.name(), "步骤已被其他执行流程领取");
    }

    private static WorkerOutcome fail(AgentRunRepository repository, SseEventWriter writer, Long runId,
                                      Long stepId, WorkerTask task, String code, String message) {
        repository.failNonSqlStep(runId, stepId, code, safe(message));
        emit(writer, "step.failed", new AgentStreamEvents.StepFailed(stepId, code, safe(message)));
        return WorkerOutcome.failure("DASHBOARD", stepId, task.attemptId(), false, code, safe(message));
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
