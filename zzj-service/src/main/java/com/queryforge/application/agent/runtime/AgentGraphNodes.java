package com.queryforge.application.agent.runtime;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.queryforge.application.agent.AgentContextAssembler;
import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentPlanStateService;
import com.queryforge.application.agent.AgentPlanner;
import com.queryforge.application.agent.AgentRoleRecorder;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.application.agent.ManagerRouter;
import com.queryforge.application.agent.PlanReviewDecision;
import com.queryforge.application.agent.PlanReviewGate;
import com.queryforge.application.agent.runtime.bus.WorkerDispatcher;
import com.queryforge.application.agent.runtime.worker.CodeAssistantWorkerAction;
import com.queryforge.application.agent.runtime.worker.DashboardWorkerAction;
import com.queryforge.application.agent.runtime.worker.DataScientistWorkerAction;
import com.queryforge.application.agent.runtime.worker.ToolAssistantWorkerAction;
import com.queryforge.application.agent.runtime.worker.WorkerAction;
import com.queryforge.application.agent.runtime.WorkerOutcome;
import com.queryforge.application.agent.runtime.worker.WorkerTask;
import com.queryforge.application.agent.runtime.worker.WorkerVerifier;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.CreateAgentRunRequest;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.PlanReviewStatus;
import com.queryforge.domain.agent.graph.GraphNode;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P14-ARCH-01：六 Agent 主执行图的节点业务委托（Manager / Planner / HITL_GATE / HITL /
 * DataScientist / CodeAssistant / ToolAssistant / Dashboard）。
 *
 * <p>每个方法只做一件事：读取 Graph 状态 + Task Context，调用已有授权业务服务，
 * 返回状态更新 map。调度位置由 Graph 边决定；MySQL 记录业务事实；SSE 只发真实事件。
 * 节点方法内不自行推进计划（派工/下一步选择统一由 Manager 完成）。
 */
@Service
public class AgentGraphNodes {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphNodes.class);

    /** 图中节点名（真实 CompiledGraph 节点 id）。 */
    public static final String NODE_MANAGER = "MANAGER";
    public static final String NODE_PLANNER = "PLANNER";
    public static final String NODE_HITL_GATE = "HITL_GATE";
    public static final String NODE_HITL = "HITL";
    public static final String NODE_DATA_SCIENTIST = "DATA_SCIENTIST";
    public static final String NODE_CODE_ASSISTANT = "CODE_ASSISTANT";
    public static final String NODE_TOOL_ASSISTANT = "TOOL_ASSISTANT";
    public static final String NODE_DASHBOARD = "DASHBOARD";

    private static final String TERMINAL_COMPLETED = "COMPLETED";
    private static final String TERMINAL_FAILED = "FAILED";
    private static final String TERMINAL_PAUSED = "PAUSED";
    private static final int MAX_HITL_REJECTIONS = 3;
    private static final int MAX_REASON_CHARS = 300;

    private final AgentRunRepository repository;
    private final ConversationService conversationService;
    private final ManagerRouter router;
    private final AgentPlanner planner;
    private final PlanReviewGate planReviewGate;
    private final AgentSqlAssistant sqlAssistant;
    private final AgentLlmCallLimiter llmLimiter;
    private final AgentRoleRecorder roleRecorder;
    private final AgentPlanStateService planState;
    private final PlanReviewSessionStore planReviewSessionStore;
    private final AgentRunFinalizer finalizer;
    private final AgentGraphStateService graphStateService;
    private final WorkerDispatcher dispatcher;
    private final WorkerVerifier workerVerifier;
    private final DataScientistWorkerAction dataScientistAction;
    private final CodeAssistantWorkerAction codeAssistantAction;
    private final ToolAssistantWorkerAction toolAssistantAction;
    private final DashboardWorkerAction dashboardAction;
    private final KnowledgeService knowledgeService;
    private final AgentContextAssembler contextAssembler;
    private final ObjectMapper objectMapper;

    public AgentGraphNodes(AgentRunRepository repository, ConversationService conversationService,
                           ManagerRouter router, AgentPlanner planner, PlanReviewGate planReviewGate,
                           AgentSqlAssistant sqlAssistant, AgentLlmCallLimiter llmLimiter,
                           AgentRoleRecorder roleRecorder, AgentPlanStateService planState,
                           PlanReviewSessionStore planReviewSessionStore, AgentRunFinalizer finalizer,
                           AgentGraphStateService graphStateService,
                           WorkerDispatcher dispatcher, WorkerVerifier workerVerifier,
                           DataScientistWorkerAction dataScientistAction,
                           CodeAssistantWorkerAction codeAssistantAction,
                           ToolAssistantWorkerAction toolAssistantAction,
                           DashboardWorkerAction dashboardAction, KnowledgeService knowledgeService,
                           AgentContextAssembler contextAssembler, ObjectMapper objectMapper) {
        this.repository = repository;
        this.conversationService = conversationService;
        this.router = router;
        this.planner = planner;
        this.planReviewGate = planReviewGate;
        this.sqlAssistant = sqlAssistant;
        this.llmLimiter = llmLimiter;
        this.roleRecorder = roleRecorder;
        this.planState = planState;
        this.planReviewSessionStore = planReviewSessionStore;
        this.finalizer = finalizer;
        this.graphStateService = graphStateService;
        this.dispatcher = dispatcher;
        this.workerVerifier = workerVerifier;
        this.dataScientistAction = dataScientistAction;
        this.codeAssistantAction = codeAssistantAction;
        this.toolAssistantAction = toolAssistantAction;
        this.dashboardAction = dashboardAction;
        this.knowledgeService = knowledgeService;
        this.contextAssembler = contextAssembler;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ MANAGER

    /**
     * Manager：路由（首次）、收集 Worker 结果、选择下一个 Ready Step、判断重试/终态/Dashboard。
     * 一次业务动作只存在唯一实际执行入口：Graph Worker Node → Dispatcher → WorkerExecutor → 业务 Service。
     */
    public Map<String, Object> manager(OverAllState state, AgentRunTaskContext ctx) {
        Map<String, Object> update = new LinkedHashMap<>();
        SseEventWriter writer = ctx.writer();

        String terminal = AgentGraphStateAccess.terminalStatus(state);
        if (terminal != null) {
            update.put(AgentGraphStateKeys.TERMINAL_STATUS, terminal);
            return update;
        }
        if (AgentGraphStateAccess.failureCode(state) != null) {
            return failNow(update, ctx, writer, AgentGraphStateAccess.failureCode(state),
                    AgentGraphStateAccess.failureMessage(state));
        }

        // 收集最近一次 Worker 结果（Manager 统一判断下一步）。
        WorkerOutcome outcome = AgentGraphStateAccess.workerOutcome(state);
        if (outcome != null) {
            update.put(AgentGraphStateKeys.WORKER_OUTCOME_JSON, null);
            GraphPlanSnapshot afterOutcome = withStepStatus(AgentGraphStateAccess.plan(state),
                    outcome.stepId(), outcome.success()
                            ? GraphPlanStep.STATUS_SUCCEEDED : GraphPlanStep.STATUS_FAILED,
                    outcome.summary(), outcome.resultRef());
            update.put(AgentGraphStateKeys.PLAN_JSON, AgentGraphStateAccess.planToJson(afterOutcome));
            if (!outcome.success()) {
                String code = outcome.failureCode() == null ? ErrorCode.AGENT_STEP_FAILED.name()
                        : outcome.failureCode();
                String message = outcome.failureReason() == null ? "步骤执行失败" : outcome.failureReason();
                failNow(update, ctx, writer, code, message);
                return update;
            }
            if (NODE_DASHBOARD.equals(outcome.role())) {
                update.put(AgentGraphStateKeys.TERMINAL_STATUS, TERMINAL_COMPLETED);
                update.put(AgentGraphStateKeys.FINAL_ANSWER, outcome.summary());
                return update;
            }
        }

        // 首次进入：Manager 路由。
        String route = AgentGraphStateAccess.route(state);
        if (route == null) {
            return routeAndPlan(state, ctx, update);
        }

        // 计划被拒绝：回 Manager → Planner 重规划（重新评估新计划，不直接解锁旧步骤）。
        // 保留 replanRequested 由 PLANNER 节点消费并清除：Manager 先行清除会让 Planner
        // 退化为首次规划，用户反馈与被拒绝计划都无法注入重规划请求。
        if (AgentGraphStateAccess.replanRequested(state)) {
            update.put(AgentGraphStateKeys.DISPATCH_ROLE, NODE_PLANNER);
            return update;
        }

        // 派发决策必须基于"已合并本次 Worker 结果"的计划（outcome 处理后的 afterOutcome）。
        GraphPlanSnapshot plan = update.containsKey(AgentGraphStateKeys.PLAN_JSON)
                ? AgentGraphStateAccess.planFromJson(
                        (String) update.get(AgentGraphStateKeys.PLAN_JSON))
                : AgentGraphStateAccess.plan(state);
        GraphPlanStep next = plan.firstReadyStep();
        if (next != null) {
            return dispatchStep(update, plan, next, route);
        }
        if (plan.allSucceeded()) {
            if (needsDashboard(route, plan)) {
                update.put(AgentGraphStateKeys.DISPATCH_ROLE, NODE_DASHBOARD);
                update.put(AgentGraphStateKeys.CURRENT_STEP_ID, null);
                update.put(AgentGraphStateKeys.ATTEMPT_ID, UUID.randomUUID().toString());
                return update;
            }
            // 简单/工具/闲聊类：Manager 自己汇总真实执行结果并终结（不与 Dashboard 重复生成）。
            List<com.queryforge.domain.agent.AgentStep> steps = repository.findSteps(ctx.runId());
            String answer = finalizer.complete(ctx, writer, steps, null);
            update.put(AgentGraphStateKeys.TERMINAL_STATUS, TERMINAL_COMPLETED);
            update.put(AgentGraphStateKeys.FINAL_ANSWER, answer);
            return update;
        }
        // 计划中既无就绪步骤也未全部成功（异常状态）：明确失败，不悬挂。
        failNow(update, ctx, writer, ErrorCode.AGENT_PLAN_INVALID.name(), "计划没有可推进的步骤");
        return update;
    }

    /** 首次进入：分类路由；闲聊/澄清/知识直接终结或暂停；SIMPLE/TOOL/COMPLEX 生成计划并派发。 */
    private Map<String, Object> routeAndPlan(OverAllState state, AgentRunTaskContext ctx,
                                             Map<String, Object> update) {
        SseEventWriter writer = ctx.writer();
        Long runId = ctx.runId();
        // 用户显式选择工具：最高优先级输入分支，跳过分类模型与 Planner，直接单步受控工具执行。
        if (ctx.toolInvocation() != null) {
            repository.updateRunRoute(runId, AgentRoute.TOOL_INVOCATION.name(), null, null);
            emit(writer, "agent.routed", new AgentStreamEvents.AgentRouted(
                    runId, AgentRoute.TOOL_INVOCATION.name(), "用户显式选择工具"));
            GraphPlanSnapshot toolPlan = singleToolPlan(ctx);
            GraphPlanSnapshot persisted = persistPlan(ctx, writer, toolPlan);
            update.put(AgentGraphStateKeys.ROUTE, AgentRoute.TOOL_INVOCATION.name());
            update.put(AgentGraphStateKeys.PLAN_JSON, AgentGraphStateAccess.planToJson(persisted));
            update.put(AgentGraphStateKeys.PLAN_VERSION, AgentGraphStateAccess.planVersion(state) + 1);
            return dispatchStep(update, persisted, persisted.steps().get(0),
                    AgentRoute.TOOL_INVOCATION.name());
        }

        ManagerRouter.RoutingContext routingContext = new ManagerRouter.RoutingContext(
                ctx.question(),
                ctx.schemaSummary() == null ? "" : ctx.schemaSummary(),
                knowledgeOf(ctx),
                ctx.effectiveContext() == null ? "" : ctx.effectiveContext().historyForClassifier());
        ManagerRouter.RouteDecision decision;
        try {
            decision = llmLimiter.count(runId, () -> router.route(routingContext));
        } catch (ApiException ex) {
            return failNow(update, ctx, writer, ex.getErrorCode().name(), safe(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("manager_route_failed runId={} message={}", runId, ex.getMessage());
            return failNow(update, ctx, writer, "AGENT_PLAN_FAILED", "路由失败，请稍后重试");
        }
        repository.updateRunRoute(runId, decision.route().name(), null, null);
        emit(writer, "agent.routed", new AgentStreamEvents.AgentRouted(
                runId, decision.route().name(), decision.reason()));
        recordRole(ctx, "MANAGER");
        update.put(AgentGraphStateKeys.ROUTE, decision.route().name());

        switch (decision.route()) {
            case CHITCHAT -> {
                String answer;
                try {
                    answer = llmLimiter.count(runId, () -> sqlAssistant.chitchatAnswer(
                            ctx.question(),
                            ctx.effectiveContext() == null ? "" : ctx.effectiveContext().historyForClassifier()));
                } catch (RuntimeException ex) {
                    return failNow(update, ctx, writer, ErrorCode.LLM_UPSTREAM_ERROR.name(), "对话生成失败");
                }
                if (answer == null || answer.isBlank()) {
                    return failNow(update, ctx, writer, ErrorCode.AGENT_STEP_FAILED.name(), "对话生成失败");
                }
                String summary = finalizer.complete(ctx, writer, List.of(), answer);
                update.put(AgentGraphStateKeys.TERMINAL_STATUS, TERMINAL_COMPLETED);
                update.put(AgentGraphStateKeys.FINAL_ANSWER, summary);
                return update;
            }
            case CLARIFY -> {
                String message = decision.reason() == null || decision.reason().isBlank()
                        ? "请补充数据源或业务口径后再提问。" : decision.reason();
                repository.updateRunStatus(runId, RunStatus.CLARIFYING, null, "AGENT_CLARIFY", message);
                emit(writer, "clarify.required", new AgentStreamEvents.ClarifyRequired(runId, message));
                // 等待用户补充：Run 非终态（CLARIFYING），Graph 流自然结束。
                update.put(AgentGraphStateKeys.TERMINAL_STATUS, TERMINAL_PAUSED);
                update.put(AgentGraphStateKeys.FINAL_ANSWER, message);
                return update;
            }
            case KNOWLEDGE -> {
                String answer = answerFromKnowledge(ctx);
                if (answer == null) {
                    return failNow(update, ctx, writer, ErrorCode.LLM_UPSTREAM_ERROR.name(), "知识作答失败");
                }
                String summary = finalizer.complete(ctx, writer, List.of(), answer);
                update.put(AgentGraphStateKeys.TERMINAL_STATUS, TERMINAL_COMPLETED);
                update.put(AgentGraphStateKeys.FINAL_ANSWER, summary);
                return update;
            }
            case SIMPLE -> {
                // 单步取数计划（角色恒为 DATA_ANALYST，避免把单步查询误判成分析/报告）。
                GraphPlanSnapshot simplePlan = new GraphPlanSnapshot(true, "", List.of(
                        new GraphPlanStep(null, 1, truncate(ctx.question(), 300), "DATA_ANALYST",
                                List.of(), null, null, GraphPlanStep.STATUS_PENDING, null, null)));
                GraphPlanSnapshot persisted = persistPlan(ctx, writer, simplePlan);
                update.put(AgentGraphStateKeys.PLAN_JSON, AgentGraphStateAccess.planToJson(persisted));
                update.put(AgentGraphStateKeys.PLAN_VERSION, AgentGraphStateAccess.planVersion(state) + 1);
                return dispatchStep(update, persisted, persisted.steps().get(0),
                        AgentRoute.SIMPLE.name());
            }
            case TOOL_INVOCATION -> {
                // 模型不得返回该值；显式工具分支已在上面处理，此处只做安全拒绝。
                return failNow(update, ctx, writer, ErrorCode.AGENT_PLAN_INVALID.name(), "路由结果不合法");
            }
            case COMPLEX, MEDIUM -> {
                // MEDIUM 是分类不可用时的安全降级：继续走 Planner，不静默放行未验证 SQL。
                update.put(AgentGraphStateKeys.DISPATCH_ROLE, NODE_PLANNER);
                return update;
            }
            default -> {
                return failNow(update, ctx, writer, ErrorCode.AGENT_PLAN_INVALID.name(), "路由结果不合法");
            }
        }
    }

    // ------------------------------------------------------------------ PLANNER

    /**
     * Planner：仅产生带角色和依赖的结构化计划（含拒绝后的重规划），验证合法角色和依赖，
     * 不执行 SQL/MCP/Python，不跳过 Gate。新计划必须先经 HITL_GATE，Manager 才可派工。
     */
    public Map<String, Object> planner(OverAllState state, AgentRunTaskContext ctx) {
        Map<String, Object> update = new LinkedHashMap<>();
        SseEventWriter writer = ctx.writer();
        Long runId = ctx.runId();
        boolean replan = AgentGraphStateAccess.replanRequested(state);
        emit(writer, "agent.started", new AgentStreamEvents.AgentStarted(runId, "PLANNER", "plan"));
        AgentPlan plan;
        try {
            if (replan) {
                // 丢弃尚未执行的旧计划，注入用户反馈重新规划；绝不回用已被拒绝的计划。
                repository.deletePendingSteps(runId);
                List<AgentPlan.Step> rejectedSteps = new ArrayList<>();
                for (GraphPlanStep step : AgentGraphStateAccess.plan(state).steps()) {
                    rejectedSteps.add(new AgentPlan.Step(step.objective(), step.role(),
                            step.dependsOn() == null || step.dependsOn().isEmpty()
                                    ? null : step.dependsOn().get(0)));
                }
                plan = llmLimiter.count(runId, () -> planner.replan(
                        runId, ctx.question(), ctx.schemaSummary(),
                        ctx.effectiveContext() == null ? "" : ctx.effectiveContext().effectiveContext(),
                        rejectedSteps, AgentGraphStateAccess.hitlFeedback(state),
                        AgentGraphStateAccess.hitlRejectionCount(state)));
            } else {
                plan = llmLimiter.count(runId, () -> planner.plan(
                        ctx.question(), ctx.schemaSummary(),
                        ctx.effectiveContext() == null ? "" : ctx.effectiveContext().effectiveContext()));
            }
        } catch (ApiException ex) {
            emit(writer, "agent.completed",
                    new AgentStreamEvents.AgentCompleted(runId, "PLANNER", "plan", "FAILED"));
            return failNow(update, ctx, writer, ex.getErrorCode().name(), safe(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("planner_failed runId={} replan={} message={}", runId, replan, ex.getMessage());
            emit(writer, "agent.completed",
                    new AgentStreamEvents.AgentCompleted(runId, "PLANNER", "plan", "FAILED"));
            return failNow(update, ctx, writer, "AGENT_PLAN_FAILED", "规划失败，请稍后重试");
        }
        emit(writer, "agent.completed",
                new AgentStreamEvents.AgentCompleted(runId, "PLANNER", "plan", "SUCCESS"));
        recordRole(ctx, "PLANNER");
        if (!plan.answerable() || plan.steps().isEmpty()) {
            String reason = plan.reason() == null || plan.reason().isBlank()
                    ? "当前数据源无法回答该问题" : plan.reason();
            return failNow(update, ctx, writer, "AGENT_UNANSWERABLE", reason);
        }
        update.put(AgentGraphStateKeys.PLAN_JSON, AgentGraphStateAccess.planToJson(toSnapshot(plan)));
        update.put(AgentGraphStateKeys.PLAN_VERSION, AgentGraphStateAccess.planVersion(state) + 1);
        update.put(AgentGraphStateKeys.REPLAN_REQUESTED, false);
        return update;
    }

    // ------------------------------------------------------------------ HITL_GATE

    /**
     * HITL_GATE：计划生成后、任何专业 Worker 真正执行之前作风险判定。
     * autoConfirm=true 不调用 Gate 模型；否则按 Gate 结果决定暂停（HITL）或回 Manager 派工。
     * 两条路径都先把已校验计划落盘（stepId 为持久事实），使刷新/恢复可展示。
     */
    public Map<String, Object> hitlGate(OverAllState state, AgentRunTaskContext ctx) {
        Map<String, Object> update = new LinkedHashMap<>();
        SseEventWriter writer = ctx.writer();
        Long runId = ctx.runId();
        GraphPlanSnapshot plan = AgentGraphStateAccess.plan(state);
        GraphPlanSnapshot persisted = persistPlan(ctx, writer, plan);
        update.put(AgentGraphStateKeys.PLAN_JSON, AgentGraphStateAccess.planToJson(persisted));

        if (ctx.autoConfirm()) {
            // autoConfirm=true：跳过 Gate 模型与人工审核（SQL Guard/沙箱/报告安全边界不变）。
            repository.markPlanReviewStatus(runId, ctx.userId(), PlanReviewStatus.SKIPPED);
            return update;
        }
        AgentRun run = repository.findRun(runId).orElse(null);
        if (run == null) {
            return failNow(update, ctx, writer, ErrorCode.AGENT_RUN_NOT_FOUND.name(), "运行不存在");
        }
        PlanReviewDecision decision = planReviewGate.evaluate(
                run, ctx.effectiveContext() == null ? "" : ctx.effectiveContext().effectiveContext(),
                toAgentPlan(persisted));
        emit(writer, "plan.review_evaluated", new AgentStreamEvents.PlanReviewEvaluated(
                runId, decision.needsReview(), decision.reasonCode(), decision.modelEvaluated()));
        if (!decision.needsReview()) {
            // Gate 判定无需人工审核（或 Gate 不可用降级）：如实记录，继续执行链。
            PlanReviewStatus status = decision.modelEvaluated()
                    ? PlanReviewStatus.NOT_REQUIRED : PlanReviewStatus.EVAL_UNAVAILABLE;
            repository.markPlanReviewStatus(runId, ctx.userId(), status);
            return update;
        }
        // 需要人工审核：进入等待（MySQL CAS 版本+1），登记短期定位信封，Graph 中断于 HITL 前。
        int version = repository.enterPlanReviewAwaiting(runId, ctx.userId(), decision.reason());
        if (version < 0) {
            return failNow(update, ctx, writer, "PLAN_REVIEW_CONFLICT", "计划审核状态冲突，请重新发起");
        }
        planReviewSessionStore.register(runId, ctx.userId(), ctx.conversationId(), ctx.workspaceId(),
                ctx.connectionId(), version, AgentGraphStateAccess.route(state));
        // 展示审计：进入等待人工审核节点（真实图即将 interruptBefore(HITL) 暂停）。
        graphStateService.enter(runId, GraphNode.WAITING_PLAN_REVIEW, writer);
        update.put(AgentGraphStateKeys.HITL_PENDING, true);
        update.put(AgentGraphStateKeys.HITL_VERSION, version);
        update.put(AgentGraphStateKeys.HITL_REASON, safe(decision.reason()));
        emit(writer, "plan.review_required", new AgentStreamEvents.PlanReviewRequired(
                runId, version, safe(decision.reason()), AgentGraphStateAccess.hitlRejectionCount(state),
                toReviewSteps(persisted)));
        // 流自然结束（interruptBefore(HITL) 暂停）；Run 非终态，未派发任何 Worker。
        return update;
    }

    // ------------------------------------------------------------------ HITL

    /**
     * HITL：Graph 前中断恢复后的唯一决策消费点。批准 → 回 Manager 按当前计划派工；
     * 拒绝 → 计数+1、旧计划作废、回 Manager → Planner 重规划；第三次拒绝终结。
     * 决策字段（hitlApproved/hitlFeedback）由 updateState 写入同一 threadId 的 checkpoint。
     */
    public Map<String, Object> hitl(OverAllState state, AgentRunTaskContext ctx) {
        Map<String, Object> update = new LinkedHashMap<>();
        SseEventWriter writer = ctx.writer();
        Long runId = ctx.runId();
        Boolean approved = AgentGraphStateAccess.hitlApproved(state);
        if (approved == null) {
            return failNow(update, ctx, writer, "CHECKPOINT_ERROR", "恢复的检查点缺少审核决定");
        }
        int version = AgentGraphStateAccess.hitlVersion(state);
        if (Boolean.TRUE.equals(approved)) {
            repository.transitionRunStatus(runId, RunStatus.RUNNING);
            emit(writer, "plan.review_decided", new AgentStreamEvents.PlanReviewDecided(
                    runId, version, true, AgentGraphStateAccess.hitlRejectionCount(state)));
            update.put(AgentGraphStateKeys.HITL_PENDING, false);
            update.put(AgentGraphStateKeys.HITL_APPROVED, null);
            return update;
        }
        int rejectionCount = repository.currentRejectionCount(runId);
        update.put(AgentGraphStateKeys.HITL_REJECTION_COUNT, rejectionCount);
        emit(writer, "plan.review_decided", new AgentStreamEvents.PlanReviewDecided(
                runId, version, false, rejectionCount));
        if (rejectionCount >= MAX_HITL_REJECTIONS) {
            // 第三次拒绝：终结 Run，绝不派发 Worker；已完成的旧计划与真实工件保留审计。
            repository.failPlanReview(runId, "PLAN_REJECTED_MAX",
                    "计划已连续 " + rejectionCount + " 次被拒绝，运行终止");
            planReviewSessionStore.removeRun(runId);
            planState.clear(runId);
            emit(writer, "plan.review_failed", new AgentStreamEvents.PlanReviewFailed(
                    runId, "PLAN_REJECTED_MAX", "计划已连续 " + rejectionCount + " 次被拒绝，运行终止"));
            graphStateService.enter(runId, GraphNode.FAILED, writer);
            update.put(AgentGraphStateKeys.TERMINAL_STATUS, TERMINAL_FAILED);
            update.put(AgentGraphStateKeys.FAILURE_CODE, "PLAN_REJECTED_MAX");
            update.put(AgentGraphStateKeys.HITL_PENDING, false);
            update.put(AgentGraphStateKeys.HITL_APPROVED, null);
            update.put(AgentGraphStateKeys.REPLAN_REQUESTED, false);
            return update;
        }
        // 1/2 次拒绝：旧计划失去待执行资格，回 Manager → Planner 重规划并再次过 Gate。
        update.put(AgentGraphStateKeys.HITL_PENDING, false);
        update.put(AgentGraphStateKeys.HITL_APPROVED, null);
        update.put(AgentGraphStateKeys.REPLAN_REQUESTED, true);
        return update;
    }

    // ------------------------------------------------------------------ 专业 Worker 节点

    /** DataScientist：SQL 生成/选优 → Guard → 真实执行（唯一执行入口经 Dispatcher）。 */
    public Map<String, Object> dataScientist(OverAllState state, AgentRunTaskContext ctx) {
        return runWorker(state, ctx, NODE_DATA_SCIENTIST, dataScientistAction);
    }

    /** CodeAssistant：已授权上游结果 → 受控 Python → Docker 沙箱 → 工件落库。 */
    public Map<String, Object> codeAssistant(OverAllState state, AgentRunTaskContext ctx) {
        return runWorker(state, ctx, NODE_CODE_ASSISTANT, codeAssistantAction);
    }

    /** ToolAssistant：受控 MCP/内置工具调用（白名单 + schema 校验 + 审计）。 */
    public Map<String, Object> toolAssistant(OverAllState state, AgentRunTaskContext ctx) {
        return runWorker(state, ctx, NODE_TOOL_ASSISTANT, toolAssistantAction);
    }

    /** Dashboard：汇总前序真实输出生成最终答复/报告（一次完成）。 */
    public Map<String, Object> dashboard(OverAllState state, AgentRunTaskContext ctx) {
        return runWorker(state, ctx, NODE_DASHBOARD, dashboardAction);
    }

    private Map<String, Object> runWorker(OverAllState state, AgentRunTaskContext ctx, String role,
                                          WorkerAction action) {
        Map<String, Object> update = new LinkedHashMap<>();
        GraphPlanSnapshot plan = AgentGraphStateAccess.plan(state);
        Long stepId = AgentGraphStateAccess.currentStepId(state);
        GraphPlanStep step = plan.findById(stepId);
        if (step == null) {
            if (NODE_DASHBOARD.equals(role) && stepId == null) {
                // 无报告步骤的汇总形态：虚拟步骤（不落库）。
                step = new GraphPlanStep(null, plan.steps().size() + 1, "汇总运行结果并生成最终答复",
                        "DASHBOARD", List.of(), null, null, GraphPlanStep.STATUS_PENDING, null, null);
            } else {
                failNow(update, ctx, ctx.writer(), ErrorCode.AGENT_STATE_CONFLICT.name(),
                        "图中步骤与计划不一致");
                return update;
            }
        }
        WorkerTask task = new WorkerTask(role, step, AgentGraphStateAccess.attemptId(state), 1, "",
                AgentGraphStateAccess.route(state), previousSummaries(plan), upstreamStepId(plan, step));
        WorkerOutcome outcome = dispatcher.dispatch(action, workerVerifier, task, ctx);
        update.put(AgentGraphStateKeys.WORKER_OUTCOME_JSON, AgentGraphStateAccess.outcomeToJson(outcome));
        return update;
    }

    // ------------------------------------------------------------------ 计划持久化与工具

    /** 计划落库（PENDING 步骤 + agent.selected/plan.ready 事件 + Redis 短期快照）。 */
    private GraphPlanSnapshot persistPlan(AgentRunTaskContext ctx, SseEventWriter writer,
                                          GraphPlanSnapshot snapshot) {
        Long runId = ctx.runId();
        List<GraphPlanStep> persisted = new ArrayList<>();
        for (GraphPlanStep step : snapshot.steps()) {
            Integer dependsOn = step.dependsOn() == null || step.dependsOn().isEmpty()
                    ? null : step.dependsOn().get(0);
            Long stepId = repository.insertStep(runId, step.sequence(), step.objective(), dependsOn,
                    step.role(), step.toolName(), step.toolArgsJson());
            persisted.add(new GraphPlanStep(stepId, step.sequence(), step.objective(), step.role(),
                    step.dependsOn(), step.toolName(), step.toolArgsJson(),
                    GraphPlanStep.STATUS_PENDING, null, null));
            emit(writer, "agent.selected", new AgentStreamEvents.AgentSelected(
                    runId, step.sequence(), step.objective(), step.role()));
        }
        emit(writer, "plan.ready", new AgentStreamEvents.PlanReady(runId,
                persisted.stream()
                        .map(step -> new AgentStreamEvents.PlanStep(
                                step.stepId(), step.sequence(), step.objective()))
                        .toList()));
        List<com.queryforge.domain.agent.AgentStep> dbSteps = repository.findSteps(runId);
        planState.save(runId, dbSteps);
        return new GraphPlanSnapshot(snapshot.answerable(), snapshot.reason(), persisted);
    }

    /** Manager 派工：设置条件边读取的 dispatchRole/currentStepId/attemptId。 */
    private Map<String, Object> dispatchStep(Map<String, Object> update, GraphPlanSnapshot plan,
                                             GraphPlanStep step, String route) {
        update.put(AgentGraphStateKeys.DISPATCH_ROLE, normalizeRole(step.role()));
        update.put(AgentGraphStateKeys.CURRENT_STEP_ID, step.stepId());
        update.put(AgentGraphStateKeys.ATTEMPT_ID, UUID.randomUUID().toString());
        return update;
    }

    /** 计划步骤角色 → 图派发角色（兼容旧 DATA_ANALYST/CODE_ANALYST/REPORT_ASSISTANT 文本）。 */
    static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return NODE_DATA_SCIENTIST;
        }
        return switch (role) {
            case "DATA_ANALYST", "SQL_AGENT" -> NODE_DATA_SCIENTIST;
            case "CODE_ANALYST" -> NODE_CODE_ASSISTANT;
            case "REPORT_ASSISTANT" -> NODE_DASHBOARD;
            case "TOOL_ASSISTANT" -> NODE_TOOL_ASSISTANT;
            default -> NODE_DATA_SCIENTIST;
        };
    }

    private static boolean needsDashboard(String route, GraphPlanSnapshot plan) {
        if (plan.hasReportStep()) {
            return false; // 报告步骤已作为计划步骤派发并完成终态
        }
        return AgentRoute.COMPLEX.name().equals(route) || AgentRoute.MEDIUM.name().equals(route);
    }

    private static String previousSummaries(GraphPlanSnapshot plan) {
        StringBuilder builder = new StringBuilder();
        for (GraphPlanStep step : plan.steps()) {
            if (GraphPlanStep.STATUS_SUCCEEDED.equals(step.status())
                    && step.resultSummary() != null && !step.resultSummary().isBlank()) {
                builder.append("步骤 ").append(step.sequence()).append("：")
                        .append(step.resultSummary()).append("\n");
            }
        }
        return builder.toString();
    }

    /** 上游 SQL 步骤：计划依赖中最近一个 DATA_ANALYST/SQL_AGENT 角色步骤。 */
    private static Long upstreamStepId(GraphPlanSnapshot plan, GraphPlanStep step) {
        if (step.dependsOn() == null || step.dependsOn().isEmpty()) {
            return null;
        }
        for (int index = step.dependsOn().size() - 1; index >= 0; index--) {
            GraphPlanStep upstream = GraphPlanStep.findBySequence(plan.steps(), step.dependsOn().get(index));
            if (upstream != null && NODE_DATA_SCIENTIST.equals(normalizeRole(upstream.role()))) {
                return upstream.stepId();
            }
        }
        return null;
    }

    private static GraphPlanSnapshot withStepStatus(GraphPlanSnapshot plan, Long stepId, String status,
                                                     String summary, Long artifactRef) {
        if (plan == null || plan.steps() == null || stepId == null) {
            return plan;
        }
        List<GraphPlanStep> updated = new ArrayList<>();
        for (GraphPlanStep step : plan.steps()) {
            if (stepId.equals(step.stepId())) {
                updated.add(new GraphPlanStep(step.stepId(), step.sequence(), step.objective(), step.role(),
                        step.dependsOn(), step.toolName(), step.toolArgsJson(), status,
                        artifactRef == null ? step.artifactRef() : artifactRef,
                        summary == null ? step.resultSummary() : summary));
            } else {
                updated.add(step);
            }
        }
        return new GraphPlanSnapshot(plan.answerable(), plan.reason(), updated);
    }

    private static GraphPlanSnapshot toSnapshot(AgentPlan plan) {
        List<GraphPlanStep> steps = new ArrayList<>();
        int sequence = 0;
        for (AgentPlan.Step step : plan.steps()) {
            sequence++;
            List<Integer> dependsOn = step.dependsOn() == null ? List.of() : List.of(step.dependsOn());
            String toolName = step.tool() == null ? null : step.tool().name();
            String toolArgsJson = null;
            if (step.tool() != null && step.tool().args() != null && !step.tool().args().isEmpty()) {
                try {
                    toolArgsJson = new ObjectMapper().writeValueAsString(step.tool().args());
                } catch (IOException ex) {
                    toolArgsJson = null;
                }
            }
            steps.add(new GraphPlanStep(null, sequence, step.objective(), step.agent(), dependsOn,
                    toolName, toolArgsJson, GraphPlanStep.STATUS_PENDING, null, null));
        }
        return new GraphPlanSnapshot(plan.answerable(), plan.reason(), steps);
    }

    private static AgentPlan toAgentPlan(GraphPlanSnapshot snapshot) {
        List<AgentPlan.Step> steps = new ArrayList<>();
        for (GraphPlanStep step : snapshot.steps()) {
            AgentPlan.ToolCall tool = step.toolName() == null ? null
                    : new AgentPlan.ToolCall(step.toolName(), Map.of());
            steps.add(new AgentPlan.Step(step.objective(), step.role(),
                    step.dependsOn() == null || step.dependsOn().isEmpty()
                            ? null : step.dependsOn().get(0), tool));
        }
        return new AgentPlan(snapshot.answerable(), snapshot.reason(), steps);
    }

    private static List<AgentStreamEvents.PlanReviewStep> toReviewSteps(GraphPlanSnapshot snapshot) {
        List<AgentStreamEvents.PlanReviewStep> steps = new ArrayList<>();
        for (GraphPlanStep step : snapshot.steps()) {
            steps.add(new AgentStreamEvents.PlanReviewStep(
                    step.stepId(), step.sequence(), step.objective(), step.role(),
                    step.dependsOn() == null || step.dependsOn().isEmpty()
                            ? null : step.dependsOn().get(0)));
        }
        return steps;
    }

    private GraphPlanSnapshot singleToolPlan(AgentRunTaskContext ctx) {
        CreateAgentRunRequest.ToolInvocationRequest tool = ctx.toolInvocation();
        String toolArgsJson = null;
        if (tool.args() != null && !tool.args().isEmpty()) {
            try {
                toolArgsJson = objectMapper.writeValueAsString(tool.args());
            } catch (IOException ex) {
                toolArgsJson = null;
            }
        }
        return new GraphPlanSnapshot(true, "", List.of(new GraphPlanStep(
                null, 1, truncate("执行工具 " + tool.toolName(), 300), "TOOL_ASSISTANT",
                List.of(), tool.toolName(), toolArgsJson, GraphPlanStep.STATUS_PENDING, null, null)));
    }

    private String answerFromKnowledge(AgentRunTaskContext ctx) {
        Long runId = ctx.runId();
        SseEventWriter writer = ctx.writer();
        try {
            List<com.queryforge.domain.knowledge.KnowledgeSearchResponse.Hit> hits =
                    knowledgeService.hits(ctx.userId(), ctx.connectionId(), ctx.question());
            try {
                contextAssembler.emitKnowledge(runId, writer, hits);
            } catch (IOException ignored) {
                // 知识来源事件为可选信息
            }
            return llmLimiter.count(runId, () -> sqlAssistant.knowledgeAnswer(ctx.question(),
                    knowledgeText(ctx)));
        } catch (RuntimeException ex) {
            log.warn("knowledge_answer_failed runId={} message={}", runId, ex.getMessage());
            return null;
        }
    }

    private String knowledgeText(AgentRunTaskContext ctx) {
        if (ctx.effectiveContext() != null && ctx.effectiveContext().knowledge() != null
                && !ctx.effectiveContext().knowledge().isBlank()) {
            return ctx.effectiveContext().knowledge();
        }
        return knowledgeService.knowledgeContext(ctx.userId(), ctx.connectionId(), ctx.question(),
                AgentContextAssembler.DEFAULT_KNOWLEDGE_CONTEXT_CHARS);
    }

    private void recordRole(AgentRunTaskContext ctx, String role) {
        repository.findRun(ctx.runId()).ifPresent(run -> roleRecorder.record(run, role));
    }

    private Map<String, Object> failNow(Map<String, Object> update, AgentRunTaskContext ctx,
                                        SseEventWriter writer, String code, String message) {
        finalizer.fail(ctx, writer, code, message);
        update.put(AgentGraphStateKeys.TERMINAL_STATUS, TERMINAL_FAILED);
        update.put(AgentGraphStateKeys.FAILURE_CODE, code);
        update.put(AgentGraphStateKeys.FAILURE_MESSAGE, safe(message));
        update.put(AgentGraphStateKeys.DISPATCH_ROLE, null);
        return update;
    }

    private static String knowledgeOf(AgentRunTaskContext ctx) {
        return ctx.effectiveContext() == null || ctx.effectiveContext().knowledge() == null
                ? "" : ctx.effectiveContext().knowledge();
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "执行失败";
        }
        return message.length() > MAX_REASON_CHARS ? message.substring(0, MAX_REASON_CHARS) : message;
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
