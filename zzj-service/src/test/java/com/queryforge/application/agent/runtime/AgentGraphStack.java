package com.queryforge.application.agent.runtime;

import com.queryforge.application.agent.AgentContextAssembler;
import com.queryforge.application.agent.AgentLlmCallLimiter;
import com.queryforge.application.agent.AgentPlanStateService;
import com.queryforge.application.agent.AgentPlanner;
import com.queryforge.application.agent.AgentPythonAssistant;
import com.queryforge.application.agent.AgentReportComposer;
import com.queryforge.application.agent.AgentRoleRecorder;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.application.agent.ManagerRouter;
import com.queryforge.application.agent.PlanReviewGate;
import com.queryforge.application.agent.RuntimeConfigResolver;
import com.queryforge.application.agent.runtime.bus.WorkerDispatcher;
import com.queryforge.application.agent.runtime.bus.AgentMessageBus;
import com.queryforge.application.agent.runtime.bus.WorkerEndpoint;
import com.queryforge.application.agent.runtime.worker.CodeAssistantWorkerAction;
import com.queryforge.application.agent.runtime.worker.DashboardWorkerAction;
import com.queryforge.application.agent.runtime.worker.DataScientistWorkerAction;
import com.queryforge.application.agent.runtime.worker.DefaultWorkerVerifier;
import com.queryforge.application.agent.runtime.worker.ToolAssistantWorkerAction;
import com.queryforge.application.agent.runtime.worker.WorkerExecutor;
import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.application.mcp.McpToolExecutionCoordinator;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.application.report.ReportService;
import com.queryforge.application.agent.roles.CodeAnalystAgent;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.tool.AgentRunToolPolicy;
import com.queryforge.application.tool.ToolOutputSanitizer;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Optional;

/**
 * P14-ARCH-01：装配真实 Graph 栈（真实 StateGraph/CompiledGraph/RedisSaver + 受控替身依赖）。
 * 每个测试用独立 threadName 前缀，避免 Redis checkpoint 串扰。
 */
public final class AgentGraphStack implements AutoCloseable {

    public final AgentRunRepository repository = Mockito.mock(AgentRunRepository.class);
    public final ConversationService conversationService = Mockito.mock(ConversationService.class);
    public final ConnectionService connectionService = Mockito.mock(ConnectionService.class);
    public final SqlExecutionService executionService = Mockito.mock(SqlExecutionService.class);
    public final ReadOnlySqlGuard guard = Mockito.mock(ReadOnlySqlGuard.class);
    public final AnalysisService analysisService = Mockito.mock(AnalysisService.class);
    public final ReportService reportService = Mockito.mock(ReportService.class);
    public final CodeAnalystAgent codeAnalyst = Mockito.mock(CodeAnalystAgent.class);
    public final ToolAssistantAgent toolAssistant = Mockito.mock(ToolAssistantAgent.class);
    public final AgentRunToolPolicy toolPolicy = Mockito.mock(AgentRunToolPolicy.class);
    public final ApprovalSessionStore approvalSessionStore;
    public final PlanReviewSessionStore planReviewSessionStore;
    public final RuntimeConfigResolver configResolver = Mockito.mock(RuntimeConfigResolver.class);
    public final KnowledgeService knowledgeService = Mockito.mock(KnowledgeService.class);
    public final McpToolExecutionCoordinator mcpCoordinator = Mockito.mock(McpToolExecutionCoordinator.class);

    public final AgentGraphTestSupport.ScriptedLlmClient llm = new AgentGraphTestSupport.ScriptedLlmClient();
    public final ObjectMapper objectMapper = new ObjectMapper();
    public final AgentProperties properties;
    public final AgentPlanStore planStore = Mockito.mock(AgentPlanStore.class);

    public final AgentGraphRunner runner;
    public final com.queryforge.application.agent.AgentRunService service;
    public final AgentMessageBus bus = new AgentMessageBus();
    public final RedissonClient redisson;
    public final String threadPrefix;

    private AgentGraphStack(AgentProperties properties, String threadPrefix) throws Exception {
        this.properties = properties;
        this.threadPrefix = threadPrefix;
        this.redisson = AgentGraphTestSupport.redisClient();
        // 审核信封使用真实 Redis（TTL/RLock/一次性领取语义纳入测试）。
        this.approvalSessionStore = new ApprovalSessionStore(redisson, objectMapper);
        this.planReviewSessionStore = new PlanReviewSessionStore(redisson, objectMapper);

        AgentPlanStateService planState = new AgentPlanStateService(planStore);
        AgentRoleRecorder roleRecorder = new AgentRoleRecorder(repository, objectMapper);
        AgentLlmCallLimiter llmLimiter = new AgentLlmCallLimiter(repository, properties);
        AgentSqlAssistant sqlAssistant = new AgentSqlAssistant(llm, objectMapper);
        AgentPythonAssistant pythonAssistant = new AgentPythonAssistant(llm, objectMapper,
                new com.queryforge.domain.analysis.AnalysisProperties(200, 262144, 8000, 20,
                        256L * 1024 * 1024, 1.0, "python:3.12-slim", "python"));
        AgentReportComposer reportComposer = new AgentReportComposer(llm, objectMapper);
        ManagerRouter router = new ManagerRouter(llm, objectMapper);
        AgentPlanner planner = new AgentPlanner(llm, objectMapper, properties);
        PlanReviewGate gate = new PlanReviewGate(llm, objectMapper, properties, llmLimiter);
        AgentContextAssembler contextAssembler = new AgentContextAssembler(
                knowledgeService, Mockito.mock(com.queryforge.application.skill.SkillGuidanceSelector.class),
                Mockito.mock(com.queryforge.application.memory.MemoryService.class), repository, objectMapper);
        AgentGraphStateService graphStateService = new AgentGraphStateService(repository, objectMapper);
        AgentRunFinalizer finalizer = new AgentRunFinalizer(repository, conversationService, sqlAssistant,
                llmLimiter, planState, approvalSessionStore, planReviewSessionStore, graphStateService);

        WorkerExecutor executor = new WorkerExecutor(properties);
        WorkerEndpoint endpoint = new WorkerEndpoint("test-worker-" + threadPrefix, bus, executor);
        WorkerDispatcher dispatcher = new WorkerDispatcher(properties, bus, endpoint, executor);

        DataScientistWorkerAction dataScientist = new DataScientistWorkerAction(
                new com.queryforge.application.agent.sql.SqlCandidateSelectionService(
                        sqlAssistant, llmLimiter,
                        new com.queryforge.application.agent.sql.SqlCandidateGenerator(sqlAssistant, llmLimiter,
                                new com.queryforge.domain.model.LlmProperties("https://example.invalid/v1", "k",
                                        "m", java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5))),
                        new com.queryforge.application.agent.sql.SqlCandidateScorer(guard, objectMapper)),
                guard, executionService, repository, sqlAssistant, llmLimiter, properties.maxRepairs());
        CodeAssistantWorkerAction codeAssistant = new CodeAssistantWorkerAction(
                pythonAssistant, codeAnalyst, executionService, repository, llmLimiter, 3);
        ToolAssistantWorkerAction toolWorker = new ToolAssistantWorkerAction(
                toolAssistant, toolPolicy, new ToolOutputSanitizer(objectMapper), repository, objectMapper);
        DashboardWorkerAction dashboard = new DashboardWorkerAction(
                reportService, reportComposer, sqlAssistant, analysisService, codeAnalyst, executionService,
                conversationService, repository, finalizer, llmLimiter);

        AgentGraphNodes nodes = new AgentGraphNodes(repository, conversationService, router, planner, gate,
                sqlAssistant, llmLimiter, roleRecorder, planState, planReviewSessionStore, finalizer,
                graphStateService, dispatcher, new DefaultWorkerVerifier(), dataScientist, codeAssistant,
                toolWorker, dashboard, knowledgeService, contextAssembler, objectMapper);
        AgentGraphFactory factory = new AgentGraphFactory(nodes);
        AgentGraphLifecycleListener listener = new AgentGraphLifecycleListener(graphStateService);
        this.runner = new AgentGraphRunner(factory, listener, redisson, repository, connectionService,
                conversationService, contextAssembler, finalizer, bus, endpoint, configResolver, objectMapper);

        // AgentRunService（Graph Runner 的业务门面：一次性领取 + CAS + 恢复）。
        this.service = new com.queryforge.application.agent.AgentRunService(
                repository, conversationService, connectionService, sqlAssistant, objectMapper,
                knowledgeService, configResolver, graphStateService, approvalSessionStore,
                Mockito.mock(com.queryforge.application.agent.AgentStepEngine.class), contextAssembler,
                new com.queryforge.application.agent.AgentRunFailureHandler(repository, graphStateService,
                        approvalSessionStore, planState),
                planState, roleRecorder, llmLimiter,
                new com.queryforge.application.agent.ToolInvocationRequestGuard(),
                gate, planReviewSessionStore, planner, runner);
    }

    /** Run 状态迁移桩：按调用切换返回的 Run（模拟 PLANNING → WAITING_PLAN_REVIEW → RUNNING）。 */
    public void stubRunStatusTransitions(java.util.function.Supplier<com.queryforge.domain.agent.AgentRun> supplier) {
        Mockito.when(repository.findRunOwned(Mockito.anyLong(), Mockito.anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(supplier.get()));
        Mockito.when(repository.findRun(Mockito.anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(supplier.get()));
    }

    public static AgentGraphStack create(AgentProperties properties) throws Exception {
        return new AgentGraphStack(properties, java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    public static AgentProperties props(int maxSteps, int maxRepairs, int maxLlmCalls, int maxWorkerAttempts,
                                        String dispatchMode) {
        return new AgentProperties(maxSteps, maxRepairs, 6000, maxLlmCalls, maxWorkerAttempts, dispatchMode);
    }

    /** 默认运行事实（PLANNING、COMPLEX 路由由测试自行桩定）。 */
    public void stubRun(long runId, long userId, AgentRun run) {
        Mockito.when(repository.findRunOwned(runId, userId)).thenReturn(Optional.of(run));
        Mockito.when(repository.findRun(runId)).thenReturn(Optional.of(run));
    }

    /** 桩住 runId↔threadId 绑定与查询（真实 Redis checkpoint 由 RedisSaver 负责）。 */
    public void stubGraphThreadBinding() {
        java.util.Map<Long, String> threads = new java.util.concurrent.ConcurrentHashMap<>();
        Mockito.doAnswer(invocation -> {
            threads.put(invocation.getArgument(0, Long.class), invocation.getArgument(1, String.class));
            return 1;
        }).when(repository).bindGraphThread(Mockito.anyLong(), Mockito.anyString());
        Mockito.when(repository.findGraphThreadId(Mockito.anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(threads.get(invocation.getArgument(0, Long.class))));
    }

    /** 用内存 Map 桩住 agent_graph_checkpoints 读写，使展示审计状态机可真实推进。 */
    public void stubGraphCheckpointStore() {
        java.util.Map<Long, String> store = new java.util.concurrent.ConcurrentHashMap<>();
        Mockito.doAnswer(invocation -> {
            store.put(invocation.getArgument(0, Long.class), invocation.getArgument(1, String.class));
            return 1;
        }).when(repository).upsertGraphCheckpoint(Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong());
        Mockito.when(repository.findGraphCheckpointJson(Mockito.anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0, Long.class))));
    }

    public void stubCommonContext(long userId, long connectionId, long conversationId, String schema) {
        Mockito.when(connectionService.schemaContext(userId, connectionId)).thenReturn(schema);
        Mockito.when(connectionService.summary(userId, connectionId))
                .thenReturn(new ConnectionService.ConnectionSummary(connectionId, "test-db", "test_db"));
        Mockito.lenient().when(conversationService.recentCompletedMessages(conversationId, 6))
                .thenReturn(java.util.List.of());
        Mockito.lenient().when(conversationService.memorySummary(conversationId)).thenReturn(null);
        Mockito.lenient().when(knowledgeService.hits(Mockito.eq(userId), Mockito.eq(connectionId),
                Mockito.anyString())).thenReturn(java.util.List.of());
        Mockito.lenient().when(knowledgeService.knowledgeContext(Mockito.eq(userId),
                Mockito.eq(connectionId), Mockito.anyString(), Mockito.anyInt())).thenReturn("");
    }

    public OutputStream silentWriter() {
        return new ByteArrayOutputStream();
    }

    public SseEventWriter capturingWriter(ByteArrayOutputStream output) {
        return new SseEventWriter(output, objectMapper);
    }

    /** 默认配置（autoConfirm=true，OFF 模式）。 */
    public static ResolvedAgentConfig defaultConfig() {
        return ResolvedAgentConfig.defaults();
    }

    @Override
    public void close() {
        redisson.shutdown();
    }
}
