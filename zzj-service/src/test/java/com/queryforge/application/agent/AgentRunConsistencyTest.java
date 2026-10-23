package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.application.memory.MemoryService;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.application.skill.SkillGuidanceSelector;
import com.queryforge.application.skill.SkillService;
import com.queryforge.application.agent.sql.SqlSelection;
import com.queryforge.application.agent.sql.SqlSelectionRequest;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.ApproveAgentStepRequest;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.graph.AgentGraphState;
import com.queryforge.domain.agent.graph.GraphNode;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.conversation.MessageRole;
import com.queryforge.domain.conversation.MessageStatus;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.query.SqlExecutionException;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * AR-01-FB01：Agent Run 生命周期与 Graph/Redis 一致性回归。
 *
 * <p>本测试使用**真实的** {@link AgentGraphStateService}（仓库由内存 Map 桩接），
 * 因此非法的图迁移会以 {@link IllegalStateException} 暴露，而不是被 mock 吞掉；
 * 用于复现并锁定「DB 已终态但 Graph/Redis 短期状态仍停留旧状态」这类不一致。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AR-01-FB01 Agent 运行一致性回归")
class AgentRunConsistencyTest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final String QUESTION = "统计订单数量";
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)";
    private static final String SQL = "SELECT COUNT(*) AS `订单数` FROM shop_orders";
    private static final String FIXED_SQL = "SELECT COUNT(*) AS `订单数` FROM shop_orders WHERE status = 'paid'";
    private static final String DATABASE = "sql_demo_a";
    private static final String CHITCHAT_REPLY =
            "你好！我是 SQL 分析助手。你可以描述一个业务问题，我会基于真实 Schema 生成只读 SQL 并等待你确认。";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    @Mock
    private AgentRunRepository repository;
    @Mock
    private ConversationService conversationService;
    @Mock
    private ConnectionService connectionService;
    @Mock
    private AgentPlanner planner;
    @Mock
    private AgentSqlAssistant sqlAssistant;
    @Mock
    private SqlExecutionService executionService;
    @Mock
    private ReadOnlySqlGuard guard;
    @Mock
    private ManagerRouter managerRouter;
    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private RuntimeConfigResolver configResolver;
    @Mock
    private SkillService skillService;
    @Mock
    private MemoryService memoryService;
    @Mock
    private AgentPlanStore planStore;
    @Mock
    private ApprovalSessionStore approvalSessionStore;
    @Mock
    private PlanReviewSessionStore planReviewSessionStore;
    @Mock
    private PlanReviewGate planReviewGate;
    @Mock
    private RoleAssigner roleAssigner;
    @Mock
    private com.queryforge.application.agent.sql.SqlCandidateSelectionService selectionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 真实图服务的持久化后端（替代 agent_graph_checkpoints 表）。 */
    private final Map<Long, String> checkpoints = new HashMap<>();

    private AgentGraphStateService graphStateService;
    private AgentPlanStateService planState;
    private AgentStepEngine engine;
    private AgentRunService service;

    @BeforeEach
    void setUp() {
        checkpoints.clear();
        graphStateService = new AgentGraphStateService(repository, objectMapper);
        lenient().when(repository.findGraphCheckpointJson(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(checkpoints.get(invocation.getArgument(0))));
        lenient().when(repository.upsertGraphCheckpoint(anyLong(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    checkpoints.put(invocation.getArgument(0), invocation.getArgument(1));
                    return 1;
                });
        lenient().when(repository.llmCallCount(anyLong())).thenReturn(0);
        lenient().when(repository.tryIncrementLlmCalls(anyLong(), anyInt())).thenReturn(1);

        AgentProperties properties = new AgentProperties(3, 2, 6000, 12);
        planState = new AgentPlanStateService(planStore);
        AgentRoleRecorder roleRecorder = new AgentRoleRecorder(repository, objectMapper);
        AgentLlmCallLimiter llmLimiter = new AgentLlmCallLimiter(repository, properties);
        AgentContextAssembler contextAssembler = new AgentContextAssembler(
                knowledgeService, new SkillGuidanceSelector(skillService), memoryService, repository, objectMapper);
        AgentRunFailureHandler failureHandler = new AgentRunFailureHandler(
                repository, graphStateService, approvalSessionStore, planState);
        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, guard, properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler,
                planState, roleRecorder, llmLimiter,
                mock(com.queryforge.application.agent.roles.ToolAssistantAgent.class),
                mock(com.queryforge.application.tool.AgentRunToolPolicy.class),
                mock(com.queryforge.application.tool.ToolOutputSanitizer.class),
                selectionService,
                mock(AgentPythonAssistant.class),
                mock(com.queryforge.application.agent.roles.CodeAnalystAgent.class),
                mock(com.queryforge.application.report.ReportService.class),
                mock(AgentReportComposer.class),
                mock(com.queryforge.application.analysis.AnalysisService.class));
        service = new AgentRunService(
                repository, conversationService, connectionService, sqlAssistant, objectMapper,
                knowledgeService, configResolver, graphStateService, approvalSessionStore,
                engine, contextAssembler, failureHandler, planState, roleRecorder, llmLimiter,
                new com.queryforge.application.agent.ToolInvocationRequestGuard(),
                planReviewGate, planReviewSessionStore, planner);
        lenient().when(roleAssigner.assign(anyString())).thenReturn(AgentRole.DATA_ANALYST);
    }

    // ------------------------------------------------------------------ 图基线

    private void seedGraphAt(GraphNode node) {
        graphStateService.initialize(RUN_ID, USER_ID, null, CONNECTION_ID, null, null, "{}", 0);
        if (node == GraphNode.CREATED) {
            return;
        }
        graphStateService.enter(RUN_ID, GraphNode.ROUTING, null);
        if (node == GraphNode.ROUTING) {
            return;
        }
        switch (node) {
            case WAITING_SQL_APPROVAL, SQL_EXECUTION -> {
                graphStateService.enter(RUN_ID, GraphNode.PLANNING, null);
                graphStateService.enter(RUN_ID, GraphNode.CONTEXT_BUILDING, null);
                graphStateService.enter(RUN_ID, GraphNode.RUNNING, null);
                graphStateService.enter(RUN_ID, GraphNode.SQL_GENERATION, null);
                graphStateService.enter(RUN_ID, GraphNode.WAITING_SQL_APPROVAL, null);
                if (node == GraphNode.SQL_EXECUTION) {
                    graphStateService.enter(RUN_ID, GraphNode.SQL_EXECUTION, null);
                }
            }
            case KNOWLEDGE -> graphStateService.enter(RUN_ID, GraphNode.KNOWLEDGE, null);
            default -> throw new IllegalArgumentException("未支持的种子节点: " + node);
        }
    }

    private GraphNode currentNode() {
        return graphStateService.load(RUN_ID).orElseThrow().node();
    }

    private long currentRevision() {
        return graphStateService.load(RUN_ID).orElseThrow().stateRevision();
    }

    // ------------------------------------------------------------ S2-6 修复路径

    @Test
    @DisplayName("AR01-08 可修复 SQL 修复后：新版本 + 回到 WAITING_SQL_APPROVAL 图节点 + 换发令牌")
    void repair_shouldRestoreWaitingApprovalNodeAndToken() throws Exception {
        seedGraphAt(GraphNode.SQL_EXECUTION);
        long revisionBefore = currentRevision();
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenThrow(fixable("unknown column"));
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(sqlAssistant.fixSql(SQL, "SQL 执行失败", QUESTION, SCHEMA)).thenReturn(FIXED_SQL);
        when(guard.validate(FIXED_SQL, DATABASE)).thenReturn(FIXED_SQL);

        String output = approve(new PreparedApproval(
                USER_ID, run(RunStatus.RUNNING), step(StepStatus.WAITING_APPROVAL, SQL, 1, 0),
                false, DATABASE, ResolvedAgentConfig.defaults()));

        assertThat(output).contains("event: step.repairing", "event: step.sql_ready", "event: approval.required");
        verify(repository).setStepSql(STEP_ID, FIXED_SQL, 2, 1);
        verify(repository).updateRunStatus(RUN_ID, RunStatus.WAITING_APPROVAL, null, null, null);
        // 修复后必须重新登记待审批令牌（新版本），否则下次确认会被判过期。
        verify(approvalSessionStore).register(RUN_ID, STEP_ID, 2, DATABASE);
        // Graph 必须回到等待审批节点，不能停留在 SQL_EXECUTION（风险 B）。
        assertThat(currentNode()).isEqualTo(GraphNode.WAITING_SQL_APPROVAL);
        assertThat(currentRevision()).isGreaterThan(revisionBefore);
    }

    @Test
    @DisplayName("AR01-08 修复后重新等待审批时补发 node.waiting_approval")
    void repair_shouldEmitNodeWaitingApproval() throws Exception {
        seedGraphAt(GraphNode.SQL_EXECUTION);
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenThrow(fixable("unknown column"));
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(sqlAssistant.fixSql(SQL, "SQL 执行失败", QUESTION, SCHEMA)).thenReturn(FIXED_SQL);
        when(guard.validate(FIXED_SQL, DATABASE)).thenReturn(FIXED_SQL);

        String output = approve(new PreparedApproval(
                USER_ID, run(RunStatus.RUNNING), step(StepStatus.WAITING_APPROVAL, SQL, 1, 0),
                false, DATABASE, ResolvedAgentConfig.defaults()));

        assertThat(output).contains("event: node.waiting_approval");
    }

    // -------------------------------------------------------- S2-6 不可修复失败

    @Test
    @DisplayName("AR01-09 不可修复失败：Run/Step/Graph 一致进入 FAILED 并清理短期状态")
    void nonFixableFailure_shouldTerminateConsistently() throws Exception {
        seedGraphAt(GraphNode.SQL_EXECUTION);
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenThrow(new SqlExecutionException(
                        ErrorCode.SQL_EXECUTION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, "查询超时",
                        null, 0, false, new SQLException("timeout")));

        String output = approve(new PreparedApproval(
                USER_ID, run(RunStatus.RUNNING), step(StepStatus.WAITING_APPROVAL, SQL, 1, 0),
                false, DATABASE, ResolvedAgentConfig.defaults()));

        assertThat(output).contains("event: step.failed", "event: run.failed", "event: node.completed");
        verify(repository).failStep(STEP_ID, "SQL_EXECUTION_ERROR", "查询超时");
        verify(repository).updateRunStatus(RUN_ID, RunStatus.FAILED, null, "SQL_EXECUTION_ERROR", "查询超时");
        assertThat(currentNode()).isEqualTo(GraphNode.FAILED);
        verify(approvalSessionStore).removeRun(RUN_ID);
        verify(planStore).remove(String.valueOf(RUN_ID));
        verifyNoInteractions(sqlAssistant);
    }

    @Test
    @DisplayName("AR01-09/11 运行时异常（事件可能写不出）：DB 终态后 Graph 也必须收敛到 FAILED")
    void runtimeFailure_shouldTerminateGraphNode() {
        seedGraphAt(GraphNode.SQL_EXECUTION);
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenThrow(new IllegalStateException("目标库连接中断"));

        approve(new PreparedApproval(
                USER_ID, run(RunStatus.RUNNING), step(StepStatus.WAITING_APPROVAL, SQL, 1, 0),
                false, DATABASE, ResolvedAgentConfig.defaults()));

        verify(repository).failStep(STEP_ID, "AGENT_STEP_FAILED", "步骤执行失败");
        verify(repository).updateRunStatus(RUN_ID, RunStatus.FAILED, null, "AGENT_STEP_FAILED", "步骤执行失败");
        assertThat(currentNode()).isEqualTo(GraphNode.FAILED);
        verify(approvalSessionStore).removeRun(RUN_ID);
    }

    @Test
    @DisplayName("AR01-11 客户端断开：不误报成功，短期状态同样清理")
    void clientDisconnect_shouldNotReportSuccess() {
        seedGraphAt(GraphNode.SQL_EXECUTION);
        // writer 写事件即断开：首个 node.* 事件就会抛 IOException，SQL 根本没机会执行
        OutputStream closed = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("客户端已断开");
            }
        };
        lenient().when(repository.failStep(STEP_ID, "AGENT_CLIENT_CLOSED", "客户端断开，执行状态未知")).thenReturn(1);

        service.executeApproval(new PreparedApproval(
                USER_ID, run(RunStatus.RUNNING), step(StepStatus.WAITING_APPROVAL, SQL, 1, 0),
                false, DATABASE, ResolvedAgentConfig.defaults()), closed);

        verify(repository).failStep(STEP_ID, "AGENT_CLIENT_CLOSED", "客户端断开，执行状态未知");
        verify(repository).updateRunStatus(
                RUN_ID, RunStatus.FAILED, null, "AGENT_CLIENT_CLOSED", "客户端断开，执行状态未知");
        assertThat(currentNode()).isEqualTo(GraphNode.FAILED);
        verify(approvalSessionStore).removeRun(RUN_ID);
    }

    @Test
    @DisplayName("AR01-11 规划期客户端断开：取消运行并清理计划快照")
    void planningDisconnect_shouldCancelAndClearPlan() {
        stubRoute(AgentRoute.SIMPLE);
        OutputStream closed = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("客户端已断开");
            }
        };
        when(repository.cancelActiveRun(RUN_ID, USER_ID)).thenReturn(1);

        service.startRun(prepared(), closed);

        verify(repository).cancelPendingSteps(RUN_ID);
        verify(planStore).remove(String.valueOf(RUN_ID));
        assertThat(currentNode()).isEqualTo(GraphNode.CANCELED);
    }

    // ------------------------------------------------------------ AR01-10 幂等

    @Test
    @DisplayName("AR01-10 重复拒绝保持幂等，不抛错也不重复写状态")
    void rejectTwice_shouldBeIdempotent() {
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run(RunStatus.CANCELED)));
        when(repository.findStep(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(step(StepStatus.CANCELED, SQL, 1, 0)));

        service.reject(USER_ID, RUN_ID, STEP_ID);

        verify(repository, never()).rejectStep(any(), anyString());
        verify(repository, never()).updateRunStatus(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AR01-10 重复取消仅首次清理，后续保持幂等")
    void cancelTwice_shouldBeIdempotent() {
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run(RunStatus.WAITING_APPROVAL)));
        when(repository.cancelActiveRun(RUN_ID, USER_ID)).thenReturn(1, 0);

        service.cancel(USER_ID, RUN_ID);
        service.cancel(USER_ID, RUN_ID);

        verify(repository).cancelPendingSteps(RUN_ID);
        // 取消是幂等清理：第二次不写业务状态，但短期状态清理保持幂等
        verify(approvalSessionStore, org.mockito.Mockito.times(2)).removeRun(RUN_ID);
        verify(planStore, org.mockito.Mockito.times(2)).remove(String.valueOf(RUN_ID));
        assertThat(currentNode()).isEqualTo(GraphNode.CANCELED);
    }

    @Test
    @DisplayName("AR01-07 两个标签页并发确认：第二次领取为 0 时判冲突，不重复执行")
    void concurrentApproval_shouldExecuteOnce() {
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run(RunStatus.WAITING_APPROVAL)));
        when(repository.findStep(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(step(StepStatus.WAITING_APPROVAL, SQL, 1, 0)));
        when(approvalSessionStore.claim(RUN_ID, STEP_ID))
                .thenReturn(ApprovalSessionStore.Claim.CLAIMED, ApprovalSessionStore.Claim.MISSING);
        when(connectionService.summary(USER_ID, CONNECTION_ID))
                .thenReturn(new ConnectionService.ConnectionSummary(CONNECTION_ID, "A 商店库", DATABASE));
        when(repository.claimStep(RUN_ID, STEP_ID, 1)).thenReturn(1);

        service.prepareApproval(USER_ID, RUN_ID, STEP_ID, new ApproveAgentStepRequest(1, true, "tab-1"));

        assertThatThrownBy(() -> service.prepareApproval(
                USER_ID, RUN_ID, STEP_ID, new ApproveAgentStepRequest(1, true, "tab-2")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_STATE_CONFLICT));
        verify(repository).claimStep(RUN_ID, STEP_ID, 1);
    }

    @Test
    @DisplayName("AR01-05 未确认前绝不执行 SQL")
    void sql_shouldNotExecuteBeforeApproval() {
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run(RunStatus.WAITING_APPROVAL)));
        when(repository.findStep(RUN_ID, STEP_ID))
                .thenReturn(Optional.of(step(StepStatus.WAITING_APPROVAL, SQL, 1, 0)));

        // confirm=false 在令牌消费与 DB 领取之前就被拒绝
        assertThatThrownBy(() -> service.prepareApproval(
                USER_ID, RUN_ID, STEP_ID, new ApproveAgentStepRequest(1, false, "tab-1")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(executionService);
        verify(repository, never()).claimStep(any(), any(), anyInt());
        verifyNoInteractions(approvalSessionStore);
    }

    // ------------------------------------------------------- AR01-04 五种 Manager 路由

    @Nested
    @DisplayName("AR01-04 五种 Manager 路由")
    class Routes {

        @Test
        @DisplayName("CHITCHAT：直接完成，不生成 SQL")
        void chitchat() {
            stubRoute(AgentRoute.CHITCHAT);
            lenient().when(sqlAssistant.chitchatAnswer(eq(QUESTION), anyString())).thenReturn(CHITCHAT_REPLY);
            lenient().when(sqlAssistant.memorySummary(null, QUESTION, CHITCHAT_REPLY)).thenReturn("摘要");

            String output = start(prepared());

            assertThat(output).contains("event: run.completed", "你好！我是 SQL 分析助手");
            verify(repository).updateRunStatus(
                    RUN_ID, RunStatus.COMPLETED, CHITCHAT_REPLY, null, null);
            assertThat(currentNode()).isEqualTo(GraphNode.COMPLETED);
            verifyNoInteractions(executionService);
        }

        @Test
        @DisplayName("CLARIFY：进入澄清态，不生成 SQL")
        void clarify() {
            stubRoute(AgentRoute.CLARIFY);

            String output = start(prepared());

            assertThat(output).contains("event: clarify.required", "请补充数据源");
            verify(repository).updateRunStatus(RUN_ID, RunStatus.CLARIFYING, null, "AGENT_CLARIFY", "请补充数据源");
            assertThat(currentNode()).isEqualTo(GraphNode.WAITING_CLARIFICATION);
            verifyNoInteractions(executionService);
        }

        @Test
        @DisplayName("KNOWLEDGE：知识库作答，不进审批")
        void knowledge() {
            stubRoute(AgentRoute.KNOWLEDGE);
            when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
            when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000))
                    .thenReturn("口径：订单数=已支付订单");
            when(sqlAssistant.knowledgeAnswer(QUESTION, "口径：订单数=已支付订单")).thenReturn("订单数按已支付统计。");
            lenient().when(sqlAssistant.memorySummary(null, QUESTION, "订单数按已支付统计。")).thenReturn("摘要");

            String output = start(prepared());

            assertThat(output).contains("event: run.completed", "订单数按已支付统计。");
            assertThat(currentNode()).isEqualTo(GraphNode.COMPLETED);
            verify(repository, never()).insertStep(any(), anyInt(), anyString(), any());
            verifyNoInteractions(executionService);
        }

        @Test
        @DisplayName("SIMPLE：单步直达生成并等待审批")
        void simple() throws Exception {
            stubRoute(AgentRoute.SIMPLE);
            stubSingleStepPlan();
            when(selectionService.generateAndSelect(any(SqlSelectionRequest.class)))
                    .thenReturn(new SqlSelection("SINGLE", SQL, 1, 1, 1, 0.0, 0.0, 0.0, false));
            when(guard.validate(SQL, DATABASE)).thenReturn(SQL);

            String output = start(prepared());

            assertThat(output).contains("event: plan.ready", "event: step.sql_ready", "event: approval.required");
            assertThat(currentNode()).isEqualTo(GraphNode.WAITING_SQL_APPROVAL);
            assertThat(graphStateService.load(RUN_ID).orElseThrow().completedNodes())
                    .contains("PLANNING", "CONTEXT_BUILDING", "RUNNING", "SQL_GENERATION");
            verify(approvalSessionStore).register(RUN_ID, STEP_ID, 1, DATABASE);
            verifyNoInteractions(executionService);
        }

        @Test
        @DisplayName("COMPLEX：经 Planner 规划后再执行")
        void complex() throws Exception {
            stubRoute(AgentRoute.COMPLEX);
            lenient().when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
            lenient().when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
            when(planner.plan(eq(QUESTION), eq(SCHEMA), anyString()))
                    .thenReturn(new AgentPlan(true, "", List.of(new AgentPlan.Step(QUESTION))));
            stubSingleStepPlan();
            when(selectionService.generateAndSelect(any(SqlSelectionRequest.class)))
                    .thenReturn(new SqlSelection("MULTI", SQL, 1, 3, 2, 0.998, 0.9, 0.939, false));
            when(guard.validate(SQL, DATABASE)).thenReturn(SQL);

            String output = start(prepared());

            assertThat(output).contains("event: plan.ready", "event: approval.required");
            assertThat(currentNode()).isEqualTo(GraphNode.WAITING_SQL_APPROVAL);
        }
    }

    // --------------------------------------------------- AR01-13 检查点/详情恢复

    @Test
    @DisplayName("AR01-13 详情返回检查点快照，revision 随迁移递增")
    void detail_shouldExposeCheckpointSnapshot() {
        seedGraphAt(GraphNode.WAITING_SQL_APPROVAL);
        long revision = currentRevision();
        AgentRun run = run(RunStatus.WAITING_APPROVAL);
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run));
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(step(StepStatus.WAITING_APPROVAL, SQL, 1, 0)));

        var detail = service.detail(USER_ID, RUN_ID);

        assertThat(detail.graphState()).isEqualTo(checkpoints.get(RUN_ID));
        AgentGraphState restored = AgentGraphState.fromJson(objectMapper, detail.graphState());
        assertThat(restored.node()).isEqualTo(GraphNode.WAITING_SQL_APPROVAL);
        assertThat(restored.stateRevision()).isEqualTo(revision);
        assertThat(restored.completedNodes()).contains("ROUTING", "PLANNING", "RUNNING", "SQL_GENERATION");
    }

    @Test
    @DisplayName("AR01-13 已成功步骤的重复确认只回放，不重放 SQL")
    void replay_shouldNotReExecute() throws Exception {
        AgentStep succeeded = new AgentStep(
                STEP_ID, RUN_ID, 1, QUESTION, null, StepStatus.SUCCEEDED, SQL, 1, "SQL_AGENT",
                ApprovalStatus.APPROVED, 0, 1, false, null, null, "返回 1 行", NOW, NOW, null, null);
        when(repository.findRunOwned(RUN_ID, USER_ID)).thenReturn(Optional.of(run(RunStatus.COMPLETED)));
        when(repository.findStep(RUN_ID, STEP_ID)).thenReturn(Optional.of(succeeded));
        when(connectionService.summary(USER_ID, CONNECTION_ID))
                .thenReturn(new ConnectionService.ConnectionSummary(CONNECTION_ID, "A 商店库", DATABASE));

        var prepared = service.prepareApproval(
                USER_ID, RUN_ID, STEP_ID, new ApproveAgentStepRequest(1, true, "tab-1"));
        String output = approve(prepared);

        assertThat(prepared.replay()).isTrue();
        assertThat(output).contains("event: step.succeeded");
        verifyNoInteractions(executionService);
    }

    // --------------------------------------------------------- AR01-15 SSE 协议

    @Test
    @DisplayName("AR01-15 SSE 帧严格为 event/data/空行并以 UTF-8 输出")
    void sse_shouldFollowWireProtocol() throws Exception {
        stubRoute(AgentRoute.SIMPLE);
        stubSingleStepPlan();
        when(selectionService.generateAndSelect(any(SqlSelectionRequest.class)))
                .thenReturn(new SqlSelection("SINGLE", SQL, 1, 1, 1, 0.0, 0.0, 0.0, false));
        when(guard.validate(SQL, DATABASE)).thenReturn(SQL);

        String output = start(prepared());

        assertThat(output).contains("event: run.started\n");
        assertThat(output).contains("event: step.sql_ready\ndata: ");
        assertThat(output).contains("订单数"); // 中文未被转义破坏
        int dataStart = output.indexOf("data: {\"stepId\"");
        assertThat(dataStart).isGreaterThan(-1);
        assertThat(output.substring(dataStart)).contains("\n\n");
        // 每个事件块之间必须有空行分隔
        assertThat(output.split("\n\n").length).isGreaterThan(3);
    }

    // ------------------------------------------------------------ 辅助构造与桩

    private void stubRoute(AgentRoute route) {
        lenient().when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        lenient().when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        lenient().when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        lenient().when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(route, "请补充数据源"));
    }

    private void stubSingleStepPlan() {
        lenient().when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        lenient().when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
        lenient().when(repository.insertStep(RUN_ID, 1, QUESTION, null, "DATA_ANALYST")).thenReturn(STEP_ID);
        lenient().when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(StepStatus.PENDING, null, 0, 0)));
    }

    private PreparedRun prepared() {
        return new PreparedRun(
                RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, DATABASE, QUESTION);
    }

    private String start(PreparedRun prepared) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.startRun(prepared, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private String approve(PreparedApproval prepared) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.executeApproval(prepared, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static SqlExecutionException fixable(String sqlState) {
        return new SqlExecutionException(
                ErrorCode.SQL_EXECUTION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, "SQL 执行失败",
                "42S22", 1054, true, new SQLException(sqlState));
    }

    private static AgentRun run(RunStatus status) {
        return new AgentRun(
                RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "A 商店库", QUESTION,
                status, null, null, null, 0, null, null, null, NOW, NOW,
                null, null, null, null,
                Boolean.TRUE, 0, null, false, null, 0, null);
    }

    private static AgentStep step(StepStatus status, String sql, int version, int repairCount) {
        return new AgentStep(
                STEP_ID, RUN_ID, 1, QUESTION, null, status, sql, version, "SQL_AGENT",
                ApprovalStatus.PENDING, repairCount, null, null, null, null, null, NOW, NOW, null, null);
    }

    private static QueryResultResponse result() {
        return new QueryResultResponse(List.of("订单数"), List.of(Map.of("订单数", 3)), 1, false);
    }
}
