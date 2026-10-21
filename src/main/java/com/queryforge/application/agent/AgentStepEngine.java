package com.queryforge.application.agent;

import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.graph.GraphNode;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.domain.knowledge.KnowledgeSearchResponse;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.query.SqlExecutionException;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.domain.conversation.MessageRole;
import com.queryforge.domain.conversation.MessageStatus;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * Agent 执行核心：Manager 路由、Planner 规划、步骤推进（SQL 生成/审批/修复）、
 * 多步骤续跑与最终总结、会话记忆摘要。
 *
 * <p>本类只负责“怎么执行”；运行生命周期编排、审批幂等裁决与对外契约在
 * {@link AgentRunService}。事实来源始终是 DB；短期状态（Redis）经由
 * {@link AgentPlanStateService} fail-soft 读写。
 */
@Service
public class AgentStepEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentStepEngine.class);
    private static final int MAX_SUMMARY_CHARS = 500;

    private final AgentRunRepository repository;
    private final ConversationService conversationService;
    private final ConnectionService connectionService;
    private final AgentPlanner planner;
    private final AgentSqlAssistant sqlAssistant;
    private final SqlExecutionService executionService;
    private final ReadOnlySqlGuard guard;
    private final AgentProperties properties;
    private final ManagerRouter managerRouter;
    private final AgentGraphStateService graphStateService;
    private final RoleAssigner roleAssigner;
    private final ApprovalSessionStore approvalSessionStore;
    private final AgentContextAssembler contextAssembler;
    private final AgentRunFailureHandler failureHandler;
    private final AgentPlanStateService planState;
    private final AgentRoleRecorder roleRecorder;
    private final AgentLlmCallLimiter llmLimiter;

    public AgentStepEngine(
            AgentRunRepository repository,
            ConversationService conversationService,
            ConnectionService connectionService,
            AgentPlanner planner,
            AgentSqlAssistant sqlAssistant,
            SqlExecutionService executionService,
            ReadOnlySqlGuard guard,
            AgentProperties properties,
            ManagerRouter managerRouter,
            AgentGraphStateService graphStateService,
            RoleAssigner roleAssigner,
            ApprovalSessionStore approvalSessionStore,
            AgentContextAssembler contextAssembler,
            AgentRunFailureHandler failureHandler,
            AgentPlanStateService planState,
            AgentRoleRecorder roleRecorder,
            AgentLlmCallLimiter llmLimiter) {
        this.repository = repository;
        this.conversationService = conversationService;
        this.connectionService = connectionService;
        this.planner = planner;
        this.sqlAssistant = sqlAssistant;
        this.executionService = executionService;
        this.guard = guard;
        this.properties = properties;
        this.managerRouter = managerRouter;
        this.graphStateService = graphStateService;
        this.roleAssigner = roleAssigner;
        this.approvalSessionStore = approvalSessionStore;
        this.contextAssembler = contextAssembler;
        this.failureHandler = failureHandler;
        this.planState = planState;
        this.roleRecorder = roleRecorder;
        this.llmLimiter = llmLimiter;
    }

    public ManagerRouter.RouteDecision route(PreparedRun run, String memory, SseEventWriter writer)
            throws IOException {
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "MANAGER", "route"));
        try {
            ManagerRouter.RouteDecision decision =
                    llmLimiter.count(run.runId(), () -> managerRouter.route(run.question(), memory));
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "MANAGER", "route", "SUCCESS"));
            return decision;
        } catch (RuntimeException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "MANAGER", "route", "FAILED"));
            throw ex;
        }
    }

    public AgentPlan plan(PreparedRun run, String schema, SseEventWriter writer) throws IOException {
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "PLANNER", "plan"));
        try {
            AgentPlan plan = llmLimiter.count(run.runId(), () -> planner.plan(run.question(), schema));
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "PLANNER", "plan", "SUCCESS"));
            roleRecorder.record(run, "PLANNER");
            if (!plan.answerable()) {
                failureHandler.failRunWithEvent(run.runId(), "AGENT_UNANSWERABLE", plan.reason(), writer);
                return null;
            }
            return plan;
        } catch (ApiException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "PLANNER", "plan", "FAILED"));
            failureHandler.failRunWithEvent(run.runId(), "AGENT_PLAN_INVALID", ex.getMessage(), writer);
            return null;
        }
    }

    public void executePlan(PreparedRun run, String schema, AgentPlan plan, SseEventWriter writer)
            throws IOException {
        List<AgentStep> steps = createSteps(run.runId(), plan);
        for (AgentStep step : steps) {
            String role = roleAssigner.assign(step.objective()).name();
            repository.updateStepRole(step.id(), role);
            writer.event("agent.selected", new AgentStreamEvents.AgentSelected(
                    run.runId(), step.sequence(), step.objective(), role));
        }
        writer.event("plan.ready", new AgentStreamEvents.PlanReady(
                run.runId(),
                steps.stream()
                        .map(step -> new AgentStreamEvents.PlanStep(step.id(), step.sequence(), step.objective()))
                        .toList()));
        // P12 S4：计划短期状态入 Redis（DB agent_steps 仍为事实来源）。
        planState.save(run.runId(), steps);
        advance(run, steps.get(0), schema, writer);
    }

    public void advance(PreparedRun run, AgentStep step, String schema, SseEventWriter writer)
            throws IOException {
        ResolvedAgentConfig config = run.config();
        boolean ragEnabled = config == null || config.ragEnabled();
        List<KnowledgeSearchResponse.Hit> hits = ragEnabled
                ? contextAssembler.knowledgeHits(run)
                : List.of();
        contextAssembler.emitKnowledge(run.runId(), writer, hits);
        int knowledgeBudget = config != null && config.memoryContextBudget() > 0
                ? config.memoryContextBudget() : AgentContextAssembler.DEFAULT_KNOWLEDGE_CONTEXT_CHARS;
        String knowledge = ragEnabled
                ? contextAssembler.knowledgeContext(run, knowledgeBudget)
                : "";

        // 选择相关 Skill 并渲染指引（记录选择证据，不泄露模板全文到普通日志）
        String skillGuidance = contextAssembler.skillGuidance(run, writer);

        // 长期 Memory 注入（尊重 Agent 配置的 memory.enabled；按 workspace 过滤权限）
        String memoryContext = contextAssembler.memoryContext(run, writer);

        graphStateService.enter(run.runId(), GraphNode.SQL_GENERATION, writer);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.runId(), "SQL_AGENT", "step-" + step.sequence()));
        writer.event("step.started", new AgentStreamEvents.StepStarted(step.id(), step.sequence()));
        String sql;
        try {
            String customPrompt = config == null ? "" : config.systemPrompt();
            sql = llmLimiter.count(run.runId(), () ->
                    sqlAssistant.generateSql(step.objective(), schema, previousSummaries(run.runId(), step.sequence()),
                            knowledge + memoryContext, customPrompt, skillGuidance));
            sql = guard.validate(sql, run.database());
        } catch (ApiException ex) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.runId(), "SQL_AGENT", "step-" + step.sequence(), "FAILED"));
            failureHandler.failStepAndRun(run.runId(), step.id(), ex.getErrorCode(), ex.getMessage(), writer);
            return;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.runId(), "SQL_AGENT", "step-" + step.sequence(), "SUCCESS"));
        roleRecorder.record(run, "SQL_AGENT");
        if (sql.isBlank()) {
            failureHandler.failStepAndRun(
                    run.runId(), step.id(), ErrorCode.AGENT_PLAN_INVALID, "模型未生成可执行的 SQL", writer);
            return;
        }

        int nextVersion = step.sqlVersion() + 1;
        repository.setStepSql(step.id(), sql, nextVersion, step.repairCount());
        repository.updateRunStatus(run.runId(), RunStatus.WAITING_APPROVAL, null, null, null);
        graphStateService.enter(run.runId(), GraphNode.WAITING_SQL_APPROVAL, writer);
        graphStateService.markWaitingApproval(run.runId(), GraphNode.WAITING_SQL_APPROVAL, writer);
        writer.event("step.sql_ready", new AgentStreamEvents.StepSqlReady(step.id(), sql, nextVersion));
        writer.event("approval.required", new AgentStreamEvents.ApprovalRequired(step.id(), nextVersion));
        // P12 S4 场景二：登记待审批短期授权上下文（RLock 保护的领取见 prepareApproval）。
        approvalSessionStore.register(run.runId(), step.id(), nextVersion, run.database());
    }

    public void executeAndContinue(PreparedApproval approval, SseEventWriter writer) throws IOException {
        AgentRun run = approval.run();
        AgentStep step = approval.step();
        graphStateService.enter(run.id(), GraphNode.SQL_EXECUTION, writer);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.id(), "SQL_AGENT", "step-" + step.sequence()));
        writer.event("step.started", new AgentStreamEvents.StepStarted(step.id(), step.sequence()));
        QueryResultResponse result = executionService.execute(
                approval.userId(),
                new ExecuteSqlRequest(run.connectionId(), run.conversationId(), step.sql()),
                run.id(),
                step.id());
        String digest = digest(result);
        repository.completeStep(step.id(), result.rowCount(), result.truncated(), digest);
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.id(), "SQL_AGENT", "step-" + step.sequence(), "SUCCESS"));
        writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(
                step.id(), result.rowCount(), result.truncated(), digest));
        roleRecorder.record(run, "SQL_AGENT");
    }

    public void handleExecutionFailure(PreparedApproval approval, SqlExecutionException ex,
                                       SseEventWriter writer) throws IOException {
        AgentRun run = approval.run();
        AgentStep step = approval.step();
        if (!ex.isFixable() || step.repairCount() >= properties.maxRepairs()) {
            failureHandler.failStepAndRun(run.id(), step.id(), ex.getErrorCode(), ex.getMessage(), writer);
            return;
        }

        int attempt = step.repairCount() + 1;
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.id(), "FIXER", "step-" + step.sequence()));
        writer.event("step.repairing", new AgentStreamEvents.StepRepairing(
                step.id(), attempt, ex.getErrorCode().name()));
        String validated;
        try {
            String schema = connectionService.schemaContext(approval.userId(), run.connectionId());
            String fixed = llmLimiter.count(run.id(), () ->
                    sqlAssistant.fixSql(step.sql(), ex.getMessage(), step.objective(), schema));
            validated = guard.validate(fixed, approval.database());
        } catch (RuntimeException fixError) {
            writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                    run.id(), "FIXER", "step-" + step.sequence(), "FAILED"));
            ErrorCode code = fixError instanceof ApiException api
                    ? api.getErrorCode()
                    : ErrorCode.AGENT_STEP_FAILED;
            failureHandler.failStepAndRun(run.id(), step.id(), code, "自动修复失败，已终止本次运行", writer);
            return;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(
                run.id(), "FIXER", "step-" + step.sequence(), "SUCCESS"));
        roleRecorder.record(run, "FIXER");
        if (validated.isBlank()) {
            failureHandler.failStepAndRun(
                    run.id(), step.id(), ErrorCode.AGENT_PLAN_INVALID, "修复后的 SQL 为空，运行终止", writer);
            return;
        }
        repository.setStepSql(step.id(), validated, step.sqlVersion() + 1, attempt);
        repository.updateRunStatus(run.id(), RunStatus.WAITING_APPROVAL, null, null, null);
        writer.event("step.sql_ready", new AgentStreamEvents.StepSqlReady(
                step.id(), validated, step.sqlVersion() + 1));
        writer.event("approval.required", new AgentStreamEvents.ApprovalRequired(
                step.id(), step.sqlVersion() + 1));
        // P12 S4 场景二：修复后重新待审批，换发新版本令牌。
        approvalSessionStore.register(run.id(), step.id(), step.sqlVersion() + 1, approval.database());
    }

    public void advanceAfterStep(PreparedApproval approval, SseEventWriter writer) throws IOException {
        AgentRun run = approval.run();
        List<AgentStep> steps = repository.findSteps(run.id());
        Optional<AgentStep> next = steps.stream()
                .filter(step -> step.sequence() > approval.step().sequence() && step.status() == StepStatus.PENDING)
                .findFirst();
        // P12 S4 场景一：步骤完成，推进短期计划状态（RLock 保护；DB 仍为准）。
        planState.advance(run.id(), approval.step().sequence());
        if (next.isPresent()) {
            PreparedRun continuation = new PreparedRun(
                    run.id(), approval.userId(), run.conversationId(),
                    run.connectionId(), approval.database(), run.question(), approval.config());
            String schema = connectionService.schemaContext(approval.userId(), run.connectionId());
            graphStateService.enter(run.id(), GraphNode.RUNNING, writer);
            advance(continuation, next.get(), schema, writer);
            return;
        }

        graphStateService.enter(run.id(), GraphNode.REPORT_GENERATION, writer);
        String stepResults = allStepSummaries(steps);
        writer.event("agent.started", new AgentStreamEvents.AgentStarted(run.id(), "INSIGHT", "summary"));
        String summary;
        try {
            summary = llmLimiter.count(run.id(), () -> sqlAssistant.summarize(run.question(), stepResults));
        } catch (RuntimeException ex) {
            log.warn("agent_summary_failed runId={} message={}", run.id(), ex.getMessage());
            summary = "查询已完成，共 " + steps.size() + " 步。\n" + stepResults;
        }
        writer.event("agent.completed", new AgentStreamEvents.AgentCompleted(run.id(), "INSIGHT", "summary", "SUCCESS"));
        roleRecorder.record(run, "INSIGHT");
        repository.updateRunStatus(run.id(), RunStatus.COMPLETED, summary, null, null);
        conversationService.appendMessage(
                run.conversationId(), MessageRole.ASSISTANT, summary, MessageStatus.COMPLETED);
        writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.id(), summary));
        // P12 S4：运行完成，清理短期状态（令牌 + 计划快照）。
        approvalSessionStore.removeRun(run.id());
        planState.clear(run.id());
        PreparedRun prepared = new PreparedRun(
                run.id(), approval.userId(), run.conversationId(), run.connectionId(), approval.database(),
                run.question(), approval.config());
        updateSessionMemory(prepared, summary);
    }

    /** 会话记忆摘要更新：失败不影响本轮结果，下次请求退回最近轮消息。 */
    public void updateSessionMemory(PreparedRun run, String answer) {
        ResolvedAgentConfig config = run.config();
        if (config != null && !config.memoryEnabled()) {
            return; // Memory 关闭：不写入会话摘要
        }
        try {
            String previous = conversationService.memorySummary(run.conversationId());
            String summary = llmLimiter.count(run.runId(), () ->
                    sqlAssistant.memorySummary(previous, run.question(), answer));
            if (summary != null && !summary.isBlank()) {
                conversationService.updateMemorySummary(run.conversationId(), summary);
            }
        } catch (RuntimeException ex) {
            log.warn("agent_memory_update_failed runId={} message={}", run.runId(), ex.getMessage());
        }
    }

    private List<AgentStep> createSteps(Long runId, AgentPlan plan) {
        for (int index = 0; index < plan.steps().size(); index++) {
            int sequence = index + 1;
            Integer dependsOn = index == 0 ? null : sequence - 1;
            repository.insertStep(runId, sequence, plan.steps().get(index).objective(), dependsOn);
        }
        return repository.findSteps(runId);
    }

    private String previousSummaries(Long runId, int beforeSequence) {
        return repository.findSteps(runId).stream()
                .filter(step -> step.sequence() < beforeSequence && step.resultSummary() != null)
                .map(step -> "步骤 " + step.sequence() + "：" + step.resultSummary())
                .collect(Collectors.joining("\n"));
    }

    private static String allStepSummaries(List<AgentStep> steps) {
        return steps.stream()
                .map(step -> "步骤 " + step.sequence() + "（" + step.objective() + "）："
                        + (step.resultSummary() == null ? "无结果" : step.resultSummary()))
                .collect(Collectors.joining("\n"));
    }

    static String digest(QueryResultResponse result) {
        if (result.rowCount() == 0) {
            return "返回 0 行";
        }
        StringBuilder builder = new StringBuilder("返回 ").append(result.rowCount()).append(" 行");
        if (result.truncated()) {
            builder.append("（已截断）");
        }
        builder.append("；");
        int rows = Math.min(result.rows().size(), 3);
        for (int index = 0; index < rows; index++) {
            Map<String, Object> row = result.rows().get(index);
            String line = row.entrySet().stream().limit(8)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            if (builder.length() + line.length() > MAX_SUMMARY_CHARS - 3) {
                break;
            }
            builder.append(line).append("; ");
        }
        return builder.length() > MAX_SUMMARY_CHARS
                ? builder.substring(0, MAX_SUMMARY_CHARS)
                : builder.toString();
    }
}
