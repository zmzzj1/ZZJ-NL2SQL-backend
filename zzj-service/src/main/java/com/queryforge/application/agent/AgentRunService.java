package com.queryforge.application.agent;

import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.domain.conversation.MessageRole;
import com.queryforge.domain.conversation.MessageStatus;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentRunResponse;
import com.queryforge.domain.agent.AgentRunSummaryResponse;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.AgentStepResponse;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.ApproveAgentStepRequest;
import com.queryforge.domain.agent.CreateAgentRunRequest;
import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.domain.agent.PlanDecisionRequest;
import com.queryforge.domain.agent.PlanReviewStatus;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.graph.GraphNode;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.domain.knowledge.KnowledgeSearchResponse;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.domain.model.ModelRouteContextHolder;
import com.queryforge.domain.query.SqlExecutionException;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * Agent 运行编排（用例层协调器）：
 * 负责运行生命周期入口（创建/启动/审批/拒绝/取消/查询）、审批幂等裁决与 SSE 输出，
 * 具体执行委托 {@link AgentStepEngine}，上下文构建委托 {@link AgentContextAssembler}，
 * 失败终结与短期状态清理委托 {@link AgentRunFailureHandler}/{@link AgentPlanStateService}。
 *
 * <p>SQL 的审批、Guard、只读执行与幂等约束保持不变：未确认的 SQL 不会执行，
 * 确认请求按 runId + stepId + sqlVersion 幂等（P4 契约 4.1）。
 *
 * <p>P14-A：Manager 自适应路由（CHITCHAT/CLARIFY/SIMPLE/COMPLEX/KNOWLEDGE/MEDIUM）、
 * 闲聊与澄清真实业务状态、用户显式工具快捷路径、分层上下文真实入模。
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final int LIST_LIMIT = 20;
    private static final int RECENT_TURNS = 6;
    /** 授权 Schema 摘要进入分类请求的最大字符数（避免无限文本）。 */
    private static final int SCHEMA_SUMMARY_CHARS = 4000;
    /** 授权知识概要进入分类请求的最大字符数。 */
    private static final int EVIDENCE_SUMMARY_CHARS = 2000;

    private final AgentRunRepository repository;
    private final ConversationService conversationService;
    private final ConnectionService connectionService;
    private final AgentSqlAssistant sqlAssistant;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;
    private final RuntimeConfigResolver configResolver;
    private final AgentGraphStateService graphStateService;
    private final ApprovalSessionStore approvalSessionStore;
    private final AgentStepEngine engine;
    private final AgentContextAssembler contextAssembler;
    private final AgentRunFailureHandler failureHandler;
    private final AgentPlanStateService planState;
    private final AgentRoleRecorder roleRecorder;
    private final AgentLlmCallLimiter llmLimiter;
    private final ToolInvocationRequestGuard toolInvocationGuard;
    private final PlanReviewGate planReviewGate;
    private final PlanReviewSessionStore planReviewSessionStore;
    private final AgentPlanner planner;

    public AgentRunService(
            AgentRunRepository repository,
            ConversationService conversationService,
            ConnectionService connectionService,
            AgentSqlAssistant sqlAssistant,
            ObjectMapper objectMapper,
            KnowledgeService knowledgeService,
            RuntimeConfigResolver configResolver,
            AgentGraphStateService graphStateService,
            ApprovalSessionStore approvalSessionStore,
            AgentStepEngine engine,
            AgentContextAssembler contextAssembler,
            AgentRunFailureHandler failureHandler,
            AgentPlanStateService planState,
            AgentRoleRecorder roleRecorder,
            AgentLlmCallLimiter llmLimiter,
            ToolInvocationRequestGuard toolInvocationGuard,
            PlanReviewGate planReviewGate,
            PlanReviewSessionStore planReviewSessionStore,
            AgentPlanner planner) {
        this.repository = repository;
        this.conversationService = conversationService;
        this.connectionService = connectionService;
        this.sqlAssistant = sqlAssistant;
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;
        this.configResolver = configResolver;
        this.graphStateService = graphStateService;
        this.approvalSessionStore = approvalSessionStore;
        this.engine = engine;
        this.contextAssembler = contextAssembler;
        this.failureHandler = failureHandler;
        this.planState = planState;
        this.roleRecorder = roleRecorder;
        this.llmLimiter = llmLimiter;
        this.toolInvocationGuard = toolInvocationGuard;
        this.planReviewGate = planReviewGate;
        this.planReviewSessionStore = planReviewSessionStore;
        this.planner = planner;
    }

    public PreparedRun prepareStart(Long userId, CreateAgentRunRequest request) {
        Long conversationId = conversationService.requireMatchingConversation(
                userId, request.conversationId(), request.connectionId());
        ConnectionService.ConnectionSummary connection =
                connectionService.summary(userId, request.connectionId());
        String question = request.userInput().trim();
        // P14-A：显式工具请求先在服务端做边界校验（工具名/参数深度/体积/非对象参数）。
        ToolInvocationRequest toolInvocation = toolInvocationGuard.validate(request.toolInvocation());

        ResolvedAgentConfig config = resolveConfig(userId, request);
        Long runId = repository.insertRun(
                userId, conversationId, connection.id(), connection.name(), question, RunStatus.PLANNING,
                config.agentId(), config.agentVersionId(),
                writeJson(config.skillVersionRefs()), writeJson(config.allowedToolIds()),
                request.autoConfirm());
        graphStateService.initialize(
                runId, userId, request.workspaceId(), connection.id(),
                config.agentId(), config.agentVersionId(), writeJson(config.skillVersionRefs()), 0);
        conversationService.appendMessage(conversationId, MessageRole.USER, question, MessageStatus.COMPLETED);
        return new PreparedRun(
                runId, userId, conversationId, connection.id(), connection.database(), question, config,
                request.workspaceId(), toolInvocation, request.autoConfirm());
    }

    /** 解析运行配置：显式指定版本 > 草稿（测试） > Agent 当前发布版 > 默认。 */
    private ResolvedAgentConfig resolveConfig(Long userId, CreateAgentRunRequest request) {
        if (request.agentVersionId() != null) {
            return configResolver.resolvePublishedByVersion(userId, request.agentVersionId());
        }
        if (request.agentId() != null) {
            if (Boolean.TRUE.equals(request.draft())) {
                return configResolver.resolveDraft(userId, request.agentId());
            }
            return configResolver.resolvePublished(userId, request.agentId());
        }
        return ResolvedAgentConfig.defaults();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            return null;
        }
    }

    public void startRun(PreparedRun run, OutputStream outputStream) {
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        // P9/P12：路由上下文（含 conversationId，供授权与模型会话亲和性）。
        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(
                run.userId(), run.workspaceId(), null, run.runId(),
                run.config() == null ? null : run.config().agentId(), run.conversationId()));
        try {
            writer.event("run.started",
                    new AgentStreamEvents.RunStarted(run.runId(), run.conversationId()));
            if (run.config() != null && run.config().agentId() != null) {
                writer.event("config.resolved", new AgentStreamEvents.ConfigResolved(
                        run.runId(), run.config().agentId(), run.config().agentVersionId(),
                        run.config().ragEnabled(), run.config().memoryEnabled()));
            }
            String schema = connectionService.schemaContext(run.userId(), run.connectionId());
            List<String> recent = conversationService.recentCompletedMessages(run.conversationId(), RECENT_TURNS);
            String memory = conversationService.memorySummary(run.conversationId());
            writer.event("context.prepared", new AgentStreamEvents.ContextPrepared(
                    run.runId(), recent.size(), memory != null && !memory.isBlank()));

            graphStateService.enter(run.runId(), GraphNode.ROUTING, writer);

            // P14-A：用户显式选择工具时是 Manager 的最高优先级输入分支：
            // 跳过分类模型与 Planner，直接走单次受控工具执行链。
            if (run.hasToolInvocation()) {
                repository.updateRunRoute(run.runId(), "TOOL_INVOCATION", null, null);
                writer.event("agent.routed", new AgentStreamEvents.AgentRouted(
                        run.runId(), "TOOL_INVOCATION", "用户显式选择工具"));
                engine.executeToolInvocation(run, writer);
                return;
            }

            String evidenceSummary = evidenceSummary(run);
            EffectiveRunContext context = contextAssembler.buildRunContext(
                    run, writer, recent, memory, truncate(schema, SCHEMA_SUMMARY_CHARS),
                    evidenceSummary, false);
            contextAssembler.emitContextCompacted(run.runId(), writer, context);

            ManagerRouter.RoutingContext routingContext = new ManagerRouter.RoutingContext(
                    run.question(),
                    truncate(schema, SCHEMA_SUMMARY_CHARS),
                    truncate(evidenceSummary, EVIDENCE_SUMMARY_CHARS),
                    context.historyForClassifier());
            ManagerRouter.RouteDecision decision = engine.route(run, routingContext, writer);
            repository.updateRunRoute(run.runId(), decision.route().name(), null, null);
            writer.event("agent.routed", new AgentStreamEvents.AgentRouted(
                    run.runId(), decision.route().name(), decision.reason()));
            roleRecorder.record(run, "MANAGER");

            switch (decision.route()) {
                case CHITCHAT -> engine.answerChitchat(run, writer, context);
                case CLARIFY -> {
                    String message = decision.reason().isBlank()
                            ? "请补充数据源或业务口径后再提问。" : decision.reason();
                    repository.updateRunStatus(run.runId(), RunStatus.CLARIFYING, null, "AGENT_CLARIFY", message);
                    graphStateService.enter(run.runId(), GraphNode.WAITING_CLARIFICATION, writer);
                    writer.event("clarify.required", new AgentStreamEvents.ClarifyRequired(run.runId(), message));
                }
                case KNOWLEDGE -> {
                    graphStateService.enter(run.runId(), GraphNode.KNOWLEDGE, writer);
                    answerFromKnowledge(run, writer, context);
                    graphStateService.enter(run.runId(), GraphNode.COMPLETED, writer);
                }
                case SIMPLE -> {
                    graphStateService.enter(run.runId(), GraphNode.PLANNING, writer);
                    graphStateService.enter(run.runId(), GraphNode.CONTEXT_BUILDING, writer);
                    graphStateService.enter(run.runId(), GraphNode.RUNNING, writer);
                    // P14-C：SIMPLE 恒为取数步骤，避免推断角色把单步查询误判成分析/报告。
                    AgentPlan plan = new AgentPlan(
                            true, "", List.of(new AgentPlan.Step(
                                    truncate(run.question(), 300), "DATA_ANALYST", null)));
                    engine.executePlan(run, schema, plan, writer, context);
                }
                case COMPLEX, MEDIUM -> {
                    // MEDIUM 是分类不可用时的安全降级：继续走 Planner，不静默放行未验证 SQL。
                    // P14-D：Planner 产出合法计划后先过 Gate（autoConfirm 跳过），再决定是否暂停审核。
                    runComplexPlan(run, schema, writer, context);
                }
                case TOOL_INVOCATION -> {
                    // 模型不得返回该值；显式工具分支已在上面处理，此处只做安全拒绝。
                    failureHandler.failRunWithEvent(run.runId(), "AGENT_PLAN_INVALID",
                            "路由结果不合法", writer);
                }
            }
        } catch (IOException ex) {
            log.info("agent_client_closed runId={} phase=planning", run.runId());
            failureHandler.cancelRunQuietly(run.userId(), run.runId());
        } catch (RuntimeException ex) {
            log.warn("agent_plan_failed runId={} message={}", run.runId(), ex.getMessage());
            // P14-C：计划本身不合法（角色/依赖越界）时保留精确错误码，便于前端区分与定位。
            boolean planInvalid = ex instanceof ApiException apiException
                    && apiException.getErrorCode() == ErrorCode.AGENT_PLAN_INVALID;
            String code = planInvalid ? "AGENT_PLAN_INVALID" : "AGENT_PLAN_FAILED";
            String message = planInvalid && ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : "规划失败，请稍后重试";
            failureHandler.failRunWithEventQuietly(run.runId(), code, message, writer);
        } finally {
            ModelRouteContextHolder.clear();
        }
    }

    /** KNOWLEDGE 路由：直接以知识库证据作答，不生成 SQL、不进入审批。 */
    private void answerFromKnowledge(PreparedRun run, SseEventWriter writer, EffectiveRunContext context)
            throws IOException {
        List<KnowledgeSearchResponse.Hit> hits =
                knowledgeService.hits(run.userId(), run.connectionId(), run.question());
        contextAssembler.emitKnowledge(run.runId(), writer, hits);
        String knowledge = context != null && !context.knowledge().isBlank()
                ? context.knowledge()
                : knowledgeService.knowledgeContext(
                        run.userId(), run.connectionId(), run.question(),
                        AgentContextAssembler.DEFAULT_KNOWLEDGE_CONTEXT_CHARS);
        String answer = llmLimiter.count(run.runId(), () -> sqlAssistant.knowledgeAnswer(run.question(), knowledge));
        roleRecorder.record(run, "KNOWLEDGE");
        repository.updateRunStatus(run.runId(), RunStatus.COMPLETED, answer, null, null);
        conversationService.appendMessage(
                run.conversationId(), MessageRole.ASSISTANT, answer, MessageStatus.COMPLETED);
        writer.event("run.completed", new AgentStreamEvents.RunCompleted(run.runId(), answer));
        engine.updateSessionMemory(run, answer);
    }

    /**
     * P14-D：COMPLEX/MEDIUM 路由的统一编排。Planner 产出合法计划后先过 Gate（autoConfirm 跳过），
     * 再决定：跳过/无需审核则继续原执行链；需要审核则持久化计划并暂停等待，不派发任何 Worker。
     */
    private void runComplexPlan(PreparedRun run, String schema, SseEventWriter writer, EffectiveRunContext context)
            throws IOException {
        graphStateService.enter(run.runId(), GraphNode.PLANNING, writer);
        AgentPlan plan = engine.plan(run, schema, writer, context);
        if (plan == null) {
            return; // 计划不合法时 engine 已按失败收敛
        }
        // 审核模式取自本次运行创建时固定的快照，不回查数据库、不读取前端新值。
        boolean autoConfirm = !Boolean.FALSE.equals(run.planAutoConfirm());
        graphStateService.enter(run.runId(), GraphNode.HITL_GATE, writer);
        if (autoConfirm) {
            // autoConfirm=true：跳过 Gate 模型与人工审核，仍走原有 SQL 审批/沙箱/报告安全边界。
            persistAndExecute(run, schema, plan, writer, context);
            return;
        }
        // Gate 需要运行实体（累计拒绝次数等）：仅在真正要评估时才回查，且仍按用户归属校验。
        PlanReviewDecision decision = planReviewGate.evaluate(
                requireRun(run.userId(), run.runId()),
                context == null ? "" : context.effectiveContext(), plan);
        writer.event("plan.review_evaluated", new AgentStreamEvents.PlanReviewEvaluated(
                run.runId(), decision.needsReview(), decision.reasonCode(), decision.modelEvaluated()));
        if (!decision.needsReview()) {
            // Gate 判定无需人工审核（或 Gate 不可用时降级）：如实记录，继续原执行链，不出现伪造等待状态。
            PlanReviewStatus status = decision.modelEvaluated()
                    ? PlanReviewStatus.NOT_REQUIRED : PlanReviewStatus.EVAL_UNAVAILABLE;
            repository.markPlanReviewStatus(run.runId(), run.userId(), status);
            persistAndExecute(run, schema, plan, writer, context);
            return;
        }
        // 需要人工审核：先把已校验计划落盘（使刷新/恢复可展示），再原子进入等待并登记短期会话。
        engine.persistPlan(run, plan, writer);
        int version = repository.enterPlanReviewAwaiting(run.runId(), run.userId(), decision.reason());
        if (version < 0) {
            failureHandler.failRunWithEvent(run.runId(), "PLAN_REVIEW_CONFLICT", "计划审核状态冲突，请重新发起", writer);
            return;
        }
        planReviewSessionStore.register(run.runId(), run.userId(), run.conversationId(), run.workspaceId(),
                run.connectionId(), version, "AGENT_RUN");
        graphStateService.enter(run.runId(), GraphNode.WAITING_PLAN_REVIEW, writer);
        writer.event("plan.review_required", new AgentStreamEvents.PlanReviewRequired(
                run.runId(), version, decision.reason(), 0,
                repository.findSteps(run.runId()).stream()
                        .map(s -> new AgentStreamEvents.PlanReviewStep(
                                s.id(), s.sequence(), s.objective(), s.agentRole(), s.dependsOn()))
                        .toList()));
        // 流自然结束；Run 非终态，未派发任何 Worker，前端可刷新后提交审核决定。
    }

    /** 持久化计划并启动 Worker（autoConfirm 与 Gate 免审共用）。 */
    private void persistAndExecute(PreparedRun run, String schema, AgentPlan plan, SseEventWriter writer,
                                  EffectiveRunContext context) throws IOException {
        graphStateService.enter(run.runId(), GraphNode.CONTEXT_BUILDING, writer);
        graphStateService.enter(run.runId(), GraphNode.RUNNING, writer);
        engine.executePlan(run, schema, plan, writer, context);
    }

    /**
     * P14-D：计划审核决定（批准/拒绝）的一次性领取与续跑/重规划。
     * 同一审核版本最多一个决定胜出：Redis 令牌优先、MySQL CAS 兜底，二者均不自动批准。
     */
    public PreparedPlanDecision preparePlanDecision(Long userId, Long runId, PlanDecisionRequest request) {
        Integer versionObj = request.hitlVersion();
        if (versionObj == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "hitlVersion 不能为空");
        }
        int hitlVersion = versionObj;
        boolean approved = Boolean.TRUE.equals(request.approved());
        AgentRun run = requireRun(userId, runId);
        if (run.status() != RunStatus.WAITING_PLAN_REVIEW) {
            throw conflict("当前运行不处于计划审核等待状态");
        }
        PlanReviewStatus current = PlanReviewStatus.parse(run.planReviewStatus());
        if (current != PlanReviewStatus.AWAITING) {
            throw conflict("当前审核版本已处理，请刷新后操作");
        }
        if (run.planReviewVersion() != hitlVersion) {
            throw conflict("审核版本已过期，请刷新后重新操作");
        }

        PlanReviewSessionStore.Claim tokenClaim = planReviewSessionStore.claim(runId, userId, hitlVersion);
        if (tokenClaim == PlanReviewSessionStore.Claim.FORBIDDEN) {
            throw new ApiException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "无权处理该运行");
        }
        if (tokenClaim == PlanReviewSessionStore.Claim.EXPIRED
                || tokenClaim == PlanReviewSessionStore.Claim.STALE_VERSION) {
            throw conflict("审核会话已过期或版本不匹配，请刷新后重新操作");
        }
        // UNAVAILABLE：Redis 不可用/锁争用，交给下方 MySQL CAS 独立裁决，不伪造已领取。
        String feedback = request.feedback() == null ? "" : request.feedback();
        if (approved) {
            if (repository.claimPlanApproval(runId, userId, hitlVersion) == 0) {
                throw conflict("该审核版本已被处理，不能重复执行");
            }
        } else if (repository.claimPlanRejection(
                runId, userId, hitlVersion, feedback, "用户拒绝计划并要求重规划") == 0) {
            throw conflict("该审核版本已被处理，不能重复执行");
        }
        planReviewSessionStore.remove(runId, hitlVersion);
        int rejectionCount = approved ? run.planRejectionCount() : run.planRejectionCount() + 1;
        return new PreparedPlanDecision(userId, runId, run, hitlVersion, approved, feedback, rejectionCount);
    }

    /** P14-D：按已领取的决定执行——批准则从持久化原计划续跑，拒绝则重规划或终结。 */
    public void continuePlanReview(PreparedPlanDecision planDecision, OutputStream outputStream) {
        Long userId = planDecision.userId();
        Long runId = planDecision.runId();
        AgentRun run = planDecision.run();
        int hitlVersion = planDecision.hitlVersion();
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(userId, null, null, runId, null, null));
        try {
            if (planDecision.approved()) {
                graphStateService.enter(runId, GraphNode.CONTEXT_BUILDING, writer);
                graphStateService.enter(runId, GraphNode.RUNNING, writer);
                repository.transitionRunStatus(runId, RunStatus.RUNNING);
                writer.event("plan.review_decided", new AgentStreamEvents.PlanReviewDecided(
                        runId, hitlVersion, true, run.planRejectionCount()));
                // 从已持久化的原计划（禁止重新生成新计划冒充所批准版本）续跑。
                PreparedRun prepared = continuationRun(run, userId);
                String schema = connectionService.schemaContext(userId, run.connectionId());
                EffectiveRunContext ctx = engine.rebuildContext(prepared, writer, schema);
                engine.resumePersistedPlan(prepared, schema, writer, ctx);
                return;
            }

            // 拒绝路径：拒绝计数已 +1、旧待定计划作废，根据计数决定终结或重规划。
            int rejectionCount = planDecision.rejectionCount();
            writer.event("plan.review_decided", new AgentStreamEvents.PlanReviewDecided(
                    runId, hitlVersion, false, rejectionCount));

            if (rejectionCount >= 3) {
                // 达到三次拒绝上限：终结 Run，绝不派发 Worker。
                graphStateService.enter(runId, GraphNode.FAILED, writer);
                repository.failPlanReview(runId, "PLAN_REJECTED_MAX",
                        "计划已连续 " + rejectionCount + " 次被拒绝，运行终止");
                writer.event("plan.review_failed", new AgentStreamEvents.PlanReviewFailed(
                        runId, "PLAN_REJECTED_MAX", "计划已连续 " + rejectionCount + " 次被拒绝，运行终止"));
                planReviewSessionStore.removeRun(runId);
                planState.clear(runId);
                return;
            }

            // 1 或 2 次拒绝：回 PLANNING，丢弃尚未执行的旧计划，注入反馈重规划并再次过 Gate。
            repository.transitionRunStatus(runId, RunStatus.PLANNING);
            graphStateService.enter(runId, GraphNode.PLANNING, writer);
            List<AgentPlan.Step> rejectedSteps = repository.findSteps(runId).stream()
                    .map(s -> new AgentPlan.Step(s.objective(), s.agentRole(), s.dependsOn()))
                    .toList();
            repository.deletePendingSteps(runId);
            PreparedRun prepared = continuationRun(run, userId);
            String schema = connectionService.schemaContext(userId, run.connectionId());
            EffectiveRunContext ctx = engine.rebuildContext(prepared, writer, schema);
            String feedback = planDecision.feedback();
            AgentPlan newPlan;
            try {
                newPlan = llmLimiter.count(runId, () -> planner.replan(
                        runId, run.question(), schema, ctx == null ? "" : ctx.effectiveContext(),
                        rejectedSteps, feedback, rejectionCount));
            } catch (ApiException ex) {
                graphStateService.enter(runId, GraphNode.FAILED, writer);
                repository.failPlanReview(runId, ex.getErrorCode().name(), ex.getMessage());
                writer.event("plan.review_failed", new AgentStreamEvents.PlanReviewFailed(
                        runId, ex.getErrorCode().name(), ex.getMessage()));
                planReviewSessionStore.removeRun(runId);
                planState.clear(runId);
                return;
            }
            if (!newPlan.answerable() || newPlan.steps().isEmpty()) {
                graphStateService.enter(runId, GraphNode.FAILED, writer);
                repository.failPlanReview(runId, "AGENT_UNANSWERABLE", "重规划未能产生可执行计划，运行终止");
                writer.event("plan.review_failed", new AgentStreamEvents.PlanReviewFailed(
                        runId, "AGENT_UNANSWERABLE", "重规划未能产生可执行计划，运行终止"));
                planReviewSessionStore.removeRun(runId);
                planState.clear(runId);
                return;
            }
            // 新计划再次过 Gate（使用 Run 固定的 autoConfirm 值）。
            PlanReviewDecision decision = planReviewGate.evaluate(
                    run, ctx == null ? "" : ctx.effectiveContext(), newPlan);
            writer.event("plan.review_evaluated", new AgentStreamEvents.PlanReviewEvaluated(
                    runId, decision.needsReview(), decision.reasonCode(), decision.modelEvaluated()));
            graphStateService.enter(runId, GraphNode.HITL_GATE, writer);
            if (!decision.needsReview()) {
                PlanReviewStatus status = decision.modelEvaluated()
                        ? PlanReviewStatus.NOT_REQUIRED : PlanReviewStatus.EVAL_UNAVAILABLE;
                repository.markPlanReviewStatus(runId, userId, status);
                graphStateService.enter(runId, GraphNode.CONTEXT_BUILDING, writer);
                graphStateService.enter(runId, GraphNode.RUNNING, writer);
                engine.executePlan(prepared, schema, newPlan, writer, ctx);
                return;
            }
            engine.persistPlan(prepared, newPlan, writer);
            int newVersion = repository.enterPlanReviewAwaiting(runId, userId, decision.reason());
            if (newVersion < 0) {
                failureHandler.failRunWithEvent(runId, "PLAN_REVIEW_CONFLICT", "计划重审状态冲突，请重新发起", writer);
                return;
            }
            planReviewSessionStore.register(runId, userId, prepared.conversationId(), prepared.workspaceId(),
                    prepared.connectionId(), newVersion, "AGENT_RUN");
            graphStateService.enter(runId, GraphNode.WAITING_PLAN_REVIEW, writer);
            writer.event("plan.replanned", new AgentStreamEvents.PlanReplanned(
                    runId, newVersion, rejectionCount,
                    repository.findSteps(runId).stream()
                            .map(s -> new AgentStreamEvents.PlanReviewStep(
                                    s.id(), s.sequence(), s.objective(), s.agentRole(), s.dependsOn()))
                            .toList()));
            // 流自然结束；新版本待审核，Run 非终态。
        } catch (IOException ex) {
            log.info("agent_client_closed runId={} phase=plan_review", runId);
            failureHandler.cancelRunQuietly(userId, runId);
        } catch (RuntimeException ex) {
            log.warn("agent_plan_review_failed runId={} message={}", runId, ex.getMessage());
            String code = ex instanceof ApiException api ? api.getErrorCode().name() : "PLAN_REVIEW_FAILED";
            String message = ex instanceof ApiException api && api.getMessage() != null
                    ? api.getMessage() : "计划审核处理失败";
            try {
                graphStateService.enter(runId, GraphNode.FAILED, writer);
                repository.failPlanReview(runId, code, message);
                writer.event("plan.review_failed", new AgentStreamEvents.PlanReviewFailed(runId, code, message));
            } catch (IOException | RuntimeException ignore) {
                // 事件发送失败不掩盖主流程错误
            }
            planReviewSessionStore.removeRun(runId);
            planState.clear(runId);
        } finally {
            ModelRouteContextHolder.clear();
        }
    }

    /** 从已持久化 Run 派生续跑 PreparedRun（配置/数据库/连接均来自服务端事实，绝不接受客户端伪造）。 */
    private PreparedRun continuationRun(AgentRun run, Long userId) {
        String database = databaseOf(userId, run);
        ResolvedAgentConfig config = configOf(run);
        return new PreparedRun(run.id(), userId, run.conversationId(), run.connectionId(),
                database, run.question(), config);
    }

    /** 构造 RunDetail 的计划审核视图（刷新恢复来源）。未触发 Gate 的旧 Run 返回 null。 */
    private AgentRunResponse.PlanReviewView toPlanReviewView(AgentRun run) {
        boolean triggered = run.planReviewStatus() != null
                || run.planReviewVersion() > 0
                || run.planRejectionCount() > 0
                || Boolean.TRUE.equals(run.planReviewRequired());
        if (!triggered) {
            return null;
        }
        boolean required = Boolean.TRUE.equals(run.planReviewRequired());
        PlanReviewStatus status = PlanReviewStatus.parse(run.planReviewStatus());
        List<AgentRunResponse.PlanStepView> planSteps = java.util.List.of();
        if (required) {
            planSteps = repository.findSteps(run.id()).stream()
                    .filter(s -> s.status() == StepStatus.PENDING || s.status() == StepStatus.RUNNING)
                    .map(s -> new AgentRunResponse.PlanStepView(
                            s.id(), s.sequence(), s.objective(), s.agentRole(), s.dependsOn()))
                    .toList();
        }
        boolean autoConfirm = run.planAutoConfirm() != null && run.planAutoConfirm();
        return new AgentRunResponse.PlanReviewView(
                required,
                status == null ? null : status.name(),
                run.planReviewVersion(),
                run.planReviewReason() == null ? "" : run.planReviewReason(),
                run.planRejectionCount(),
                autoConfirm,
                planSteps);
    }

    /** 授权知识概要：仅使用当前 userId/connectionId/workspaceId 授权范围内的检索结果。 */
    private String evidenceSummary(PreparedRun run) {
        ResolvedAgentConfig config = run.config();
        if (config != null && !config.ragEnabled()) {
            return "";
        }
        try {
            return contextAssembler.knowledgeContext(
                    run, AgentContextAssembler.DEFAULT_KNOWLEDGE_CONTEXT_CHARS);
        } catch (RuntimeException ex) {
            log.debug("agent_evidence_summary_skipped runId={} message={}", run.runId(), ex.getMessage());
            return "";
        }
    }

    public PreparedApproval prepareApproval(Long userId, Long runId, Long stepId,
                                            ApproveAgentStepRequest request) {
        AgentRun run = requireRun(userId, runId);
        AgentStep step = requireStep(runId, stepId);
        if (!Boolean.TRUE.equals(request.confirm())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "必须显式确认后才可执行");
        }
        if (step.sqlVersion() <= 0 || step.sqlVersion() != request.sqlVersion()) {
            throw conflict("SQL 版本已变化，请刷新后重新确认");
        }
        if (step.status() == StepStatus.SUCCEEDED) {
            return new PreparedApproval(userId, run, step, true, databaseOf(userId, run), configOf(run));
        }
        if (run.status().terminal()) {
            throw conflict("运行已结束，不能继续确认");
        }
        if (step.status() != StepStatus.WAITING_APPROVAL) {
            throw conflict("该步骤当前不可确认");
        }
        // P12 S4 场景二：先消费短期授权令牌（RLock 一次性读取+删除，防并发重复领取）。
        // MISSING=已过期/已被消费；UNAVAILABLE=Redis 不可用或锁争用，交回 DB claim 裁决。
        ApprovalSessionStore.Claim tokenClaim = approvalSessionStore.claim(runId, stepId);
        if (tokenClaim == ApprovalSessionStore.Claim.MISSING) {
            throw conflict("审批上下文已过期或已被处理，请刷新后重新确认");
        }
        String database = databaseOf(userId, run);
        int claimed = repository.claimStep(runId, stepId, request.sqlVersion());
        if (claimed == 0) {
            AgentStep latest = requireStep(runId, stepId);
            if (latest.status() == StepStatus.SUCCEEDED && latest.sqlVersion() == request.sqlVersion()) {
                return new PreparedApproval(userId, run, latest, true, database, configOf(run));
            }
            throw conflict("该步骤已被处理，不能重复执行");
        }
        repository.updateRunStatus(runId, RunStatus.RUNNING, null, null, null);
        return new PreparedApproval(userId, run, step, false, database, configOf(run));
    }

    /** 从 Run 固定的 agentVersionId 重新解析配置（版本不可变，结果稳定）。 */
    private ResolvedAgentConfig configOf(AgentRun run) {
        return configResolver.resolveForRun(run.userId(), run.agentId(), run.agentVersionId());
    }

    public void executeApproval(PreparedApproval approval, OutputStream outputStream) {
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        AgentRun run = approval.run();
        AgentStep step = approval.step();
        // P9/P12：继续执行的 LLM 调用同样携带路由上下文（会话亲和性按会话生效）。
        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(
                approval.userId(), null, null, run.id(), run.agentId(), run.conversationId()));
        try {
            if (approval.replay()) {
                writer.event("step.succeeded", new AgentStreamEvents.StepSucceeded(
                        step.id(), step.rowCount() == null ? 0 : step.rowCount(),
                        Boolean.TRUE.equals(step.truncated()),
                        step.resultSummary() == null ? "" : step.resultSummary()));
                // P14-C：幂等重放时继续推进剩余就绪步骤（跨请求恢复，不重复执行 SQL）。
                engine.advanceAfterApprovedStep(approval, null, writer);
                return;
            }
            ApprovedSqlOutcome outcome = engine.executeAndContinue(approval, writer);
            engine.advanceAfterApprovedStep(approval, outcome, writer);
        } catch (IOException ex) {
            log.info("agent_client_closed runId={} stepId={}", run.id(), step.id());
            failureHandler.markInterrupted(run.id(), step.id());
        } catch (SqlExecutionException ex) {
            try {
                engine.handleExecutionFailure(approval, ex, writer);
            } catch (IOException | RuntimeException emitError) {
                log.info("agent_failure_event_skipped runId={} stepId={}", run.id(), step.id());
                failureHandler.markInterrupted(run.id(), step.id());
            }
        } catch (ApiException ex) {
            try {
                failureHandler.failStepAndRun(run.id(), step.id(), ex.getErrorCode(), ex.getMessage(), writer);
            } catch (IOException | RuntimeException emitError) {
                log.info("agent_failure_event_skipped runId={} stepId={}", run.id(), step.id());
            }
        } catch (RuntimeException ex) {
            log.warn("agent_step_failed runId={} stepId={} message={}", run.id(), step.id(), ex.getMessage());
            failureHandler.failStepAndRunQuietly(run.id(), step.id(), "AGENT_STEP_FAILED", "步骤执行失败", writer);
        } finally {
            ModelRouteContextHolder.clear();
        }
    }

    @Transactional
    public void reject(Long userId, Long runId, Long stepId) {
        AgentRun run = requireRun(userId, runId);
        AgentStep step = requireStep(runId, stepId);
        if (run.status() == RunStatus.CANCELED || step.status() == StepStatus.CANCELED) {
            return;
        }
        if (step.status() != StepStatus.WAITING_APPROVAL) {
            throw conflict("该步骤当前不可拒绝");
        }
        repository.rejectStep(stepId, "用户拒绝执行该 SQL");
        repository.updateRunStatus(runId, RunStatus.CANCELED, null, "AGENT_STEP_REJECTED", "用户拒绝执行该 SQL");
        graphStateService.enter(runId, GraphNode.CANCELED, null);
        // P12 S4：拒绝后解除待审批令牌与计划快照。
        approvalSessionStore.remove(runId, stepId);
        planState.clear(runId);
    }

    @Transactional
    public void cancel(Long userId, Long runId) {
        requireRun(userId, runId);
        int changed = repository.cancelActiveRun(runId, userId);
        if (changed > 0) {
            repository.cancelPendingSteps(runId);
        }
        graphStateService.enter(runId, GraphNode.CANCELED, null);
        // P12 S4：取消后解除该运行全部待审批令牌与计划快照（含计划级 HITL 会话）。
        approvalSessionStore.removeRun(runId);
        planReviewSessionStore.removeRun(runId);
        planState.clear(runId);
    }

    public AgentRunResponse detail(Long userId, Long runId) {
        AgentRun run = requireRun(userId, runId);
        List<AgentStep> steps = repository.findSteps(runId);
        return new AgentRunResponse(
                run.id(), run.conversationId(), run.connectionId(), run.connectionName(),
                run.question(), run.status(), run.route(), roleRecorder.parseRoles(run.roleActivities()),
                parseSources(run.knowledgeSources()), run.llmCallCount(),
                run.summary(), run.errorCode(), run.errorMessage(),
                run.createdAt(), run.updatedAt(), steps.stream().map(AgentRunService::toStepResponse).toList(),
                repository.findGraphCheckpointJson(run.id()).orElse(null),
                toPlanReviewView(run));
    }

    public List<AgentRunSummaryResponse> listByConversation(Long userId, Long conversationId) {
        conversationService.requireMatchingConversation(userId, conversationId, null);
        return repository.findRuns(userId, conversationId, LIST_LIMIT).stream()
                .map(run -> new AgentRunSummaryResponse(
                        run.id(), run.conversationId(), run.connectionId(), run.connectionName(),
                        run.question(), run.status(), run.createdAt(), run.updatedAt()))
                .toList();
    }

    public Optional<AgentRunResponse> latest(Long userId, Long conversationId) {
        conversationService.requireMatchingConversation(userId, conversationId, null);
        return repository.findLatestRun(userId, conversationId).map(run -> detail(userId, run.id()));
    }

    private List<AgentRunResponse.KnowledgeSourceRef> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgentRunResponse.KnowledgeSourceRef>>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private AgentRun requireRun(Long userId, Long runId) {
        return repository.findRunOwned(runId, userId).orElseThrow(() -> new ApiException(
                ErrorCode.AGENT_RUN_NOT_FOUND, HttpStatus.NOT_FOUND, "运行不存在或无权访问"));
    }

    private AgentStep requireStep(Long runId, Long stepId) {
        return repository.findStep(runId, stepId).orElseThrow(() -> new ApiException(
                ErrorCode.AGENT_STEP_NOT_FOUND, HttpStatus.NOT_FOUND, "步骤不存在或无权访问"));
    }

    private String databaseOf(Long userId, AgentRun run) {
        if (run.connectionId() == null) {
            throw new ApiException(
                    ErrorCode.CONNECTION_NOT_FOUND, HttpStatus.NOT_FOUND, "运行关联的数据源已删除");
        }
        return connectionService.summary(userId, run.connectionId()).database();
    }

    private static AgentStepResponse toStepResponse(AgentStep step) {
        return new AgentStepResponse(
                step.id(), step.sequence(), step.objective(), step.dependsOn(), step.status(),
                step.sql(), step.sqlVersion(), step.agentRole(), step.approvalStatus(), step.repairCount(),
                step.rowCount(), step.truncated(), step.errorCode(), step.errorMessage(), step.resultSummary(),
                step.analysisRunId(), step.reportId());
    }

    private static ApiException conflict(String message) {
        return new ApiException(ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.CONFLICT, message);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }
}
