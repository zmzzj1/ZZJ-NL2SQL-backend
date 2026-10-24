package com.queryforge.application.agent.runtime;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.queryforge.application.agent.AgentContextAssembler;
import com.queryforge.application.agent.runtime.AgentRunFinalizer;
import com.queryforge.application.agent.EffectiveRunContext;
import com.queryforge.application.agent.PreparedRun;
import com.queryforge.application.agent.RuntimeConfigResolver;
import com.queryforge.application.agent.runtime.bus.AgentMessageBus;
import com.queryforge.application.agent.runtime.bus.BusMessage;
import com.queryforge.application.agent.runtime.bus.WorkerEndpoint;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.model.ModelRouteContextHolder;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P14-ARCH-01：六 Agent 主执行图的唯一 Runner（CompiledGraph 单次编译、进程内复用）。
 *
 * <p>职责：
 * <ul>
 *   <li>编译期：{@link RedisSaver} 注册进 {@link CompileConfig}，{@code interruptBefore(HITL)}，
 *       真实图 checkpoint 持久化到 Redis；</li>
 *   <li>启动：创建 Run 时生成唯一 threadId 并持久化归属映射（runId↔threadId），
 *       携带 Task Context 启动同一个 {@link CompiledGraph}；</li>
 *   <li>恢复：同一 threadId 上 {@code updateState(...).withResume()} 后
 *       {@code stream(null, resumedConfig)}，HITL 批准/拒绝都在同一条图上继续；</li>
 *   <li>查询：{@link #snapshotOf(Long)} 返回真实 {@link StateSnapshot}（next/checkpointId），
 *       供详情接口与审核提交校验；checkpoint 丢失或无权查看时显式失败。</li>
 * </ul>
 *
 * <p>Graph 不在 Controller 内反复 compile，也不从数据库猜测 next 节点；
 * 旧 {@code AgentStepEngine} 不再为同一 Run 决定节点与执行顺序。
 */
@Service
public class AgentGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphRunner.class);
    private static final int RECENT_TURNS = 6;
    private static final int RECURSION_LIMIT = 60;

    private final CompiledGraph compiledGraph;
    private final AgentRunRepository repository;
    private final ConnectionService connectionService;
    private final ConversationService conversationService;
    private final AgentContextAssembler contextAssembler;
    private final AgentRunFinalizer finalizer;
    private final AgentMessageBus bus;
    private final WorkerEndpoint endpoint;
    private final RuntimeConfigResolver configResolver;
    private final ObjectMapper objectMapper;

    public AgentGraphRunner(AgentGraphFactory factory, AgentGraphLifecycleListener lifecycleListener,
                            RedissonClient redisson, AgentRunRepository repository,
                            ConnectionService connectionService, ConversationService conversationService,
                            AgentContextAssembler contextAssembler, AgentRunFinalizer finalizer,
                            AgentMessageBus bus, WorkerEndpoint endpoint,
                            RuntimeConfigResolver configResolver, ObjectMapper objectMapper)
            throws Exception {
        this.repository = repository;
        this.connectionService = connectionService;
        this.conversationService = conversationService;
        this.contextAssembler = contextAssembler;
        this.finalizer = finalizer;
        this.bus = bus;
        this.endpoint = endpoint;
        this.configResolver = configResolver;
        this.objectMapper = objectMapper;

        StateGraph graph = factory.buildGraph();
        RedisSaver redisSaver = RedisSaver.builder()
                .redisson(redisson)
                .stateSerializer(StateGraph.DEFAULT_JACKSON_SERIALIZER)
                .build();
        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(redisSaver).build())
                .interruptBefore(AgentGraphNodes.NODE_HITL)
                .recursionLimit(RECURSION_LIMIT)
                .withLifecycleListener(lifecycleListener)
                .build();
        this.compiledGraph = graph.compile(compileConfig);

        // SWITCH 模式：Worker 端点消费线程（守护线程；进程重启后以 Graph checkpoint 恢复）。
        bus.registerEndpoint(endpoint.endpointId());
        bus.startConsumer(endpoint.endpointId(),
                message -> {
                    if (message instanceof BusMessage.TaskDispatch dispatch) {
                        endpoint.handle(dispatch);
                    }
                });
    }

    /** 编译后的六 Agent 主执行图（测试与诊断用）。 */
    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 启动一次运行（唯一主执行入口）。threadId 与 Run 归属映射持久化到 MySQL。
     *
     * @return 本次运行的 threadId
     */
    public String start(PreparedRun run, SseEventWriter writer) {
        String threadId = UUID.randomUUID().toString();
        int bound = repository.bindGraphThread(run.runId(), threadId);
        if (bound == 0) {
            throw new ApiException(ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.CONFLICT,
                    "运行状态冲突，无法绑定图线程");
        }
        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(
                run.userId(), run.workspaceId(), null, run.runId(),
                run.config() == null ? null : run.config().agentId(), run.conversationId()));
        try {
            String schema = connectionService.schemaContext(run.userId(), run.connectionId());
            List<String> recent = conversationService.recentCompletedMessages(run.conversationId(), RECENT_TURNS);
            String memory = conversationService.memorySummary(run.conversationId());
            AgentRunTaskContext taskContext;
            EffectiveRunContext context;
            try {
                context = contextAssembler.buildRunContext(
                        run, writer, recent, memory, schema, evidenceSummary(run), false);
                taskContext = AgentRunTaskContext.fromPreparedRun(
                        run, threadId, writer, schema, context);
            } catch (IOException ex) {
                log.info("agent_context_build_failed runId={} threadId={}", run.runId(), threadId);
                AgentRunTaskContext fallback = AgentRunTaskContext.fromPreparedRun(
                        run, threadId, writer, schema, EffectiveRunContext.empty());
                finalizer.fail(fallback, null, "AGENT_CONTEXT_FAILED", "运行上下文构建失败");
                return threadId;
            }
            try {
                writer.event("run.started", new AgentStreamEvents.RunStarted(run.runId(), run.conversationId(),
                        threadId));
                if (run.config() != null && run.config().agentId() != null) {
                    writer.event("config.resolved", new AgentStreamEvents.ConfigResolved(
                            run.runId(), run.config().agentId(), run.config().agentVersionId(),
                            run.config().ragEnabled(), run.config().memoryEnabled()));
                }
                writer.event("context.prepared", new AgentStreamEvents.ContextPrepared(
                        run.runId(), recent.size(), memory != null && !memory.isBlank()));
                contextAssembler.emitContextCompacted(run.runId(), writer, context);
            } catch (IOException ex) {
                // 握手阶段即断线：真实图尚未启动，按失败收敛（不与执行中断混为同一事件）。
                log.info("agent_client_closed_early runId={} threadId={}", run.runId(), threadId);
                finalizer.fail(taskContext, null, "AGENT_CLIENT_CLOSED", "客户端连接中断");
                return threadId;
            }
            Map<String, Object> initial = AgentGraphStateAccess.initialMap(run.runId());
            RunnableConfig config = AgentGraphFactory.configWithContext(threadId, taskContext);
            compiledGraph.stream(initial, config).collectList().block();
            return threadId;
        } catch (RuntimeException ex) {
            log.warn("agent_graph_start_failed runId={} threadId={} message={}",
                    run.runId(), threadId, ex.getMessage(), ex);
            AgentRunTaskContext taskContext = AgentRunTaskContext.fromPreparedRun(
                    run, threadId, writer, schemaOrEmpty(run), EffectiveRunContext.empty());
            String code = ex instanceof ApiException api ? api.getErrorCode().name()
                    : ErrorCode.AGENT_STEP_FAILED.name();
            String message = ex instanceof ApiException api && api.getMessage() != null
                    ? api.getMessage() : "运行执行失败";
            finalizer.fail(taskContext, writer, code, message);
            return threadId;
        } finally {
            ModelRouteContextHolder.clear();
        }
    }

    private String schemaOrEmpty(PreparedRun run) {
        try {
            return connectionService.schemaContext(run.userId(), run.connectionId());
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String evidenceSummary(PreparedRun run) {
        ResolvedAgentConfig config = run.config();
        if (config != null && !config.ragEnabled()) {
            return "";
        }
        try {
            return contextAssembler.knowledgeContext(run, AgentContextAssembler.DEFAULT_KNOWLEDGE_CONTEXT_CHARS);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------ HITL 恢复

    /**
     * 提交审核决定并在同一 threadId 上恢复真实 Graph。
     *
     * <p>调用方（{@code AgentRunService.preparePlanDecision}）已完成一次性领取
     * （Redis 信封 + MySQL CAS）；本方法只负责真实图操作：
     * 读取 {@link StateSnapshot} 校验仍暂停于 HITL 且版本匹配 →
     * {@code updateState(config, decisionFields).withResume()} →
     * 对同一 threadId {@code stream(null, resumedConfig)}。
     *
     * @throws ApiException CHECKPOINT_ERROR（快照缺失/未暂停/版本不匹配/更新失败）
     */
    public void resumeDecision(Long runId, Long userId, int hitlVersion, boolean approved, String feedback,
                               SseEventWriter writer) {
        String threadId = requireThreadId(runId, userId);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        StateSnapshot snapshot;
        try {
            snapshot = compiledGraph.getState(config);
        } catch (RuntimeException ex) {
            log.warn("agent_graph_snapshot_missing runId={} threadId={} message={}",
                    runId, threadId, ex.getMessage());
            throw checkpointError("检查点不可用，请重新发起运行");
        }
        if (snapshot == null || !AgentGraphNodes.NODE_HITL.equals(snapshot.next())) {
            throw checkpointError("运行未暂停于人工审核，可能已被处理");
        }
        Integer stateVersion = snapshot.state().value(AgentGraphStateKeys.HITL_VERSION, Integer.class)
                .orElse(null);
        if (stateVersion == null || stateVersion != hitlVersion) {
            throw new ApiException(ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.CONFLICT,
                    "审核版本已过期，请刷新后重新操作");
        }
        AgentRun run = repository.findRunOwned(runId, userId).orElseThrow(() -> new ApiException(
                ErrorCode.AGENT_RUN_NOT_FOUND, HttpStatus.NOT_FOUND, "运行不存在或无权访问"));
        AgentRunTaskContext taskContext = buildResumeContext(run, threadId, writer);
        ModelRouteContextHolder.set(taskContext.modelRouteContext());
        try {
            Map<String, Object> decision = Map.of(
                    AgentGraphStateKeys.HITL_APPROVED, approved,
                    AgentGraphStateKeys.HITL_FEEDBACK, feedback == null ? "" : feedback);
            RunnableConfig updated;
            try {
                updated = compiledGraph.updateState(
                        AgentGraphFactory.configWithContext(threadId, taskContext), decision);
            } catch (Exception ex) {
                log.warn("agent_graph_update_state_failed runId={} threadId={} message={}",
                        runId, threadId, ex.getMessage());
                throw checkpointError("检查点更新失败，请刷新后重试");
            }
            RunnableConfig resumed = updated.withResume();
            compiledGraph.stream(null, resumed).collectList().block();
        } catch (RuntimeException ex) {
            log.warn("agent_graph_resume_failed runId={} threadId={} message={}",
                    runId, threadId, ex.getMessage());
            if (ex instanceof ApiException api) {
                throw api;
            }
            throw checkpointError("检查点恢复失败，请刷新后重试");
        } finally {
            ModelRouteContextHolder.clear();
        }
    }

    /** 真实图快照（详情/校验用）；checkpoint 不存在时返回 empty（不伪造可恢复状态）。 */
    public Optional<StateSnapshot> snapshotOf(Long runId) {
        return repository.findGraphThreadId(runId)
                .flatMap(threadId -> {
                    try {
                        return compiledGraph.stateOf(RunnableConfig.builder().threadId(threadId).build());
                    } catch (RuntimeException ex) {
                        log.warn("agent_graph_state_of_failed runId={} threadId={} message={}",
                                runId, threadId, ex.getMessage());
                        return Optional.empty();
                    }
                });
    }

    /** 同 threadId 的最新 checkpoint 是否存在（用于恢复前快速校验）。 */
    public boolean hasCheckpoint(Long runId) {
        return snapshotOf(runId).isPresent();
    }

    /**
     * 真实图暂停视图（供审核提交前的服务端校验，不泄露 Graph API 类型）。
     * 返回空 = 无 checkpoint（不可恢复，不得伪造等待状态）。
     */
    public Optional<AgentGraphPause> pauseOf(Long runId) {
        return snapshotOf(runId).map(snapshot -> new AgentGraphPause(
                snapshot.next(),
                snapshot.state().value(AgentGraphStateKeys.HITL_VERSION, Integer.class).orElse(0),
                snapshot.config().checkPointId().orElse(null)));
    }

    /** 真实图暂停视图（nextNode/hitlVersion/checkpointId）。 */
    public record AgentGraphPause(String nextNode, int hitlVersion, String checkpointId) {

        public boolean pausedAtHitl() {
            return AgentGraphNodes.NODE_HITL.equals(nextNode);
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private AgentRunTaskContext buildResumeContext(AgentRun run, String threadId, SseEventWriter writer) {
        String schema = schemaOrEmpty(run.userId(), run.connectionId());
        EffectiveRunContext context;
        try {
            PreparedRun prepared = new PreparedRun(run.id(), run.userId(), run.conversationId(),
                    run.connectionId(), databaseOf(run.userId(), run),
                    run.question(), configResolver.resolveForRun(run.userId(), run.agentId(),
                    run.agentVersionId()));
            List<String> recent = conversationService.recentCompletedMessages(run.conversationId(), RECENT_TURNS);
            String memory = conversationService.memorySummary(run.conversationId());
            context = contextAssembler.buildRunContext(
                    prepared, writer, recent, memory, schema, evidenceSummary(prepared), false);
        } catch (IOException | RuntimeException ex) {
            log.warn("agent_graph_resume_context_degraded runId={} message={}", run.id(), ex.getMessage());
            context = EffectiveRunContext.empty();
        }
        return new AgentRunTaskContext(
                run.id(), run.userId(), null, run.conversationId(), run.connectionId(),
                databaseOf(run.userId(), run), run.question(), threadId,
                configResolver.resolveForRun(run.userId(), run.agentId(), run.agentVersionId()),
                run.planAutoConfirm() == null || run.planAutoConfirm(),
                null,
                new ModelRouteContextHolder.Context(run.userId(), null, null, run.id(), run.agentId(),
                        run.conversationId()),
                writer, schema, context);
    }

    private String databaseOf(Long userId, AgentRun run) {
        if (run.connectionId() == null) {
            return "";
        }
        try {
            return connectionService.summary(userId, run.connectionId()).database();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String schemaOrEmpty(Long userId, Long connectionId) {
        try {
            return connectionService.schemaContext(userId, connectionId);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String requireThreadId(Long runId, Long userId) {
        return repository.findGraphThreadId(runId)
                .orElseThrow(() -> checkpointError("运行未绑定图线程，无法恢复"));
    }

    private static ApiException checkpointError(String message) {
        return new ApiException(ErrorCode.AGENT_STATE_CONFLICT, HttpStatus.CONFLICT, message);
    }
}
