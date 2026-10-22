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
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.graph.GraphNode;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
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
            ToolInvocationRequestGuard toolInvocationGuard) {
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
                writeJson(config.skillVersionRefs()), writeJson(config.allowedToolIds()));
        graphStateService.initialize(
                runId, userId, request.workspaceId(), connection.id(),
                config.agentId(), config.agentVersionId(), writeJson(config.skillVersionRefs()), 0);
        conversationService.appendMessage(conversationId, MessageRole.USER, question, MessageStatus.COMPLETED);
        return new PreparedRun(
                runId, userId, conversationId, connection.id(), connection.database(), question, config,
                request.workspaceId(), toolInvocation);
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
                    AgentPlan plan = new AgentPlan(
                            true, "", List.of(new AgentPlan.Step(truncate(run.question(), 300))));
                    engine.executePlan(run, schema, plan, writer, context);
                }
                case COMPLEX, MEDIUM -> {
                    // MEDIUM 是分类不可用时的安全降级：继续走 Planner，不静默放行未验证 SQL。
                    graphStateService.enter(run.runId(), GraphNode.PLANNING, writer);
                    AgentPlan plan = engine.plan(run, schema, writer, context);
                    if (plan != null) {
                        graphStateService.enter(run.runId(), GraphNode.CONTEXT_BUILDING, writer);
                        graphStateService.enter(run.runId(), GraphNode.RUNNING, writer);
                        engine.executePlan(run, schema, plan, writer, context);
                    }
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
            failureHandler.failRunWithEventQuietly(run.runId(), "AGENT_PLAN_FAILED", "规划失败，请稍后重试", writer);
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
                return;
            }
            engine.executeAndContinue(approval, writer);
            engine.advanceAfterStep(approval, writer);
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
        // P12 S4：取消后解除该运行全部待审批令牌与计划快照。
        approvalSessionStore.removeRun(runId);
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
                repository.findGraphCheckpointJson(run.id()).orElse(null));
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
                step.rowCount(), step.truncated(), step.errorCode(), step.errorMessage(), step.resultSummary());
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
