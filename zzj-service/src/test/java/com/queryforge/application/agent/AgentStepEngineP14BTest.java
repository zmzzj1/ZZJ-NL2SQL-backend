package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.agent.sql.SqlCandidateGenerator;
import com.queryforge.application.agent.sql.SqlCandidateScorer;
import com.queryforge.application.agent.sql.SqlCandidateSelectionService;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.application.memory.MemoryService;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.application.skill.SkillGuidanceSelector;
import com.queryforge.application.skill.SkillService;
import com.queryforge.application.tool.AgentRunToolPolicy;
import com.queryforge.application.tool.ToolOutputSanitizer;
import com.queryforge.domain.agent.AgentPlan;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.model.LlmProperties;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P14-B：SQL 步骤入口与选优服务的端到端集成（真实 Guard / 评分器 / 候选生成 / 选优服务，
 * 模型与持久化为 Mock）。覆盖 SIMPLE 单路径、COMPLEX 多候选、SSE 候选摘要与失败收敛。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P14-B SQL 候选选进入口")
class AgentStepEngineP14BTest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final String QUESTION = "统计每月订单金额并找出峰值";
    private static final String OBJECTIVE = "按月统计订单金额";
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)";
    private static final String DATABASE = "sql_demo_a";
    private static final String SHORT_SQL = "SELECT id FROM shop_orders";
    private static final String LONG_SQL =
            "SELECT id, amount, status FROM shop_orders WHERE status = 'paid' AND amount > 100";
    private static final String DANGEROUS_SQL = "SELECT SLEEP(5) FROM shop_orders";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 9, 0);

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
    private AgentGraphStateService graphStateService;
    @Mock
    private RoleAssigner roleAssigner;
    @Mock
    private AgentPlanStore planStore;
    @Mock
    private ApprovalSessionStore approvalSessionStore;
    @Mock
    private PlanReviewSessionStore planReviewSessionStore;
    @Mock
    private PlanReviewGate planReviewGate;
    @Mock
    private ToolAssistantAgent toolAssistant;
    @Mock
    private AgentRunToolPolicy toolPolicy;
    @Mock
    private ToolOutputSanitizer toolOutputSanitizer;

    @Mock
    private AgentPythonAssistant pythonAssistant;

    @Mock
    private com.queryforge.application.agent.roles.CodeAnalystAgent codeAnalyst;

    @Mock
    private com.queryforge.application.report.ReportService reportService;

    @Mock
    private AgentReportComposer reportComposer;

    @Mock
    private com.queryforge.application.analysis.AnalysisService analysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentStepEngine engine;
    private AgentRunService service;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties(3, 2, 6000, 12);
        AgentPlanStateService planState = new AgentPlanStateService(planStore);
        AgentRoleRecorder roleRecorder = new AgentRoleRecorder(repository, objectMapper);
        AgentLlmCallLimiter llmLimiter = new AgentLlmCallLimiter(repository, properties);
        AgentContextAssembler contextAssembler = new AgentContextAssembler(
                knowledgeService, new SkillGuidanceSelector(skillService), memoryService, repository, objectMapper);
        AgentRunFailureHandler failureHandler = new AgentRunFailureHandler(
                repository, graphStateService, approvalSessionStore, planState);
        SqlCandidateGenerator generator = new SqlCandidateGenerator(
                sqlAssistant, llmLimiter,
                new LlmProperties("https://example.invalid/v1", "key", "model",
                        Duration.ofSeconds(5), Duration.ofSeconds(5)));
        SqlCandidateScorer scorer = new SqlCandidateScorer(new ReadOnlySqlGuard(), objectMapper);
        SqlCandidateSelectionService selectionService =
                new SqlCandidateSelectionService(sqlAssistant, llmLimiter, generator, scorer);
        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, new ReadOnlySqlGuard(), properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler,
                planState, roleRecorder, llmLimiter, toolAssistant, toolPolicy, toolOutputSanitizer,
                selectionService, pythonAssistant, codeAnalyst, reportService, reportComposer,
                analysisService);
        service = new AgentRunService(
                repository, conversationService, connectionService, sqlAssistant, objectMapper,
                knowledgeService, configResolver, graphStateService, approvalSessionStore,
                engine, contextAssembler, failureHandler, planState, roleRecorder, llmLimiter,
                new ToolInvocationRequestGuard(),
                planReviewGate, planReviewSessionStore, planner);

        lenient().when(repository.tryIncrementLlmCalls(anyLong(), anyInt())).thenReturn(1);
        lenient().when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        lenient().when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        lenient().when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        lenient().when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        lenient().when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
        lenient().when(roleAssigner.assign(anyString())).thenReturn(AgentRole.DATA_ANALYST);
    }

    private PreparedRun prepared() {
        return new PreparedRun(
                RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, DATABASE, QUESTION);
    }

    private AgentRun run(RunStatus status, String route) {
        return new AgentRun(
                RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "A 商店库", QUESTION,
                status, route, null, null, 0, null, null, null, NOW, NOW,
                null, null, null, null,
                Boolean.TRUE, 0, null, false, null, 0, null);
    }

    private void stubSingleStepPlan(AgentRoute route) {
        // 路由事实来自 DB 中本次 run 的记录（不是单例共享开关）。
        when(repository.findRun(RUN_ID)).thenReturn(Optional.of(run(RunStatus.RUNNING, route.name())));
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(route, "需要多步分析"));
        when(planner.plan(eq(QUESTION), eq(SCHEMA), anyString()))
                .thenReturn(new AgentPlan(true, "", List.of(new AgentPlan.Step(OBJECTIVE))));
        when(repository.insertStep(RUN_ID, 1, OBJECTIVE, null, "DATA_ANALYST")).thenReturn(STEP_ID);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                new AgentStep(STEP_ID, RUN_ID, 1, OBJECTIVE, null, StepStatus.PENDING, null, 0,
                        "SQL_AGENT", ApprovalStatus.PENDING, 0, null, null, null, null, null, NOW, NOW,
                        null, null)));
    }

    private String start(PreparedRun prepared) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.startRun(prepared, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ COMPLEX

    @Test
    @DisplayName("COMPLEX：三候选 → Guard 拦截危险候选 → 一次评分 → 唯一 SQL 待审批，审批前零执行")
    void complexRoute_shouldEvaluateCandidatesAndEmitSingleSqlReady() {
        stubSingleStepPlan(AgentRoute.COMPLEX);
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenAnswer(invocation -> switch (invocation.<Integer>getArgument(7)) {
                    case 1 -> SHORT_SQL;
                    case 2 -> LONG_SQL;
                    default -> DANGEROUS_SQL;
                });
        when(sqlAssistant.scoreCandidates(eq(OBJECTIVE), eq(SCHEMA), any()))
                .thenReturn("[{\"candidate\":1,\"score\":5},{\"candidate\":2,\"score\":9}]");

        String output = start(prepared());

        // 选优摘要在 step.sql_ready 之前，且只回传脱敏计数与分值。
        assertThat(output).contains("event: step.sql_candidates_evaluated");
        assertThat(output.indexOf("event: step.sql_candidates_evaluated"))
                .isLessThan(output.indexOf("event: step.sql_ready"));
        assertThat(output).contains("\"mode\":\"MULTI\"", "\"totalCandidates\":3", "\"validCandidates\":2");
        assertThat(output).contains("\"selectedCandidateIndex\":2", "\"ruleFallback\":false");
        // SSE 最终只有一个待审批 SQL。
        assertThat(output.split("event: step.sql_ready", -1).length - 1).isEqualTo(1);
        assertThat(output).contains("event: approval.required");
        verify(repository).setStepSql(STEP_ID, LONG_SQL, 1, 0);
        verify(repository).updateRunStatus(RUN_ID, RunStatus.WAITING_APPROVAL, null, null, null);
        verify(approvalSessionStore).register(RUN_ID, STEP_ID, 1, DATABASE);
        // 评审只看到通过安全过滤的候选。
        verify(sqlAssistant).scoreCandidates(eq(OBJECTIVE), eq(SCHEMA), eq(List.of(SHORT_SQL, LONG_SQL)));
        // 审批前绝不执行 SQL。
        verify(executionService, never()).execute(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("COMPLEX：候选请求携带可信 Run 上下文（目标/Schema/授权上下文/方言）")
    void complexRoute_shouldPassTrustedContextToCandidates() {
        stubSingleStepPlan(AgentRoute.COMPLEX);
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenReturn(SHORT_SQL, LONG_SQL, DANGEROUS_SQL);
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenReturn("[{\"candidate\":1,\"score\":6},{\"candidate\":2,\"score\":7}]");

        start(prepared());

        ArgumentCaptor<String> objective = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> schema = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dialect = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> index = ArgumentCaptor.forClass(Integer.class);
        verify(sqlAssistant, org.mockito.Mockito.times(3)).generateCandidateSql(
                objective.capture(), schema.capture(), anyString(), anyString(), anyString(), anyString(),
                anyString(), index.capture(), anyString(), dialect.capture());
        assertThat(objective.getAllValues()).containsOnly(OBJECTIVE);
        assertThat(schema.getAllValues()).containsOnly(SCHEMA);
        assertThat(dialect.getAllValues()).containsOnly("MySQL 8");
        assertThat(index.getAllValues()).containsExactlyInAnyOrder(1, 2, 3);
        // 路由来自 DB 中本次 run 的事实，而不是单例共享开关。
        verify(repository, org.mockito.Mockito.atLeastOnce()).findRun(RUN_ID);
    }

    // ------------------------------------------------------------------- SIMPLE

    @Test
    @DisplayName("SIMPLE：只调用一次生成模型，不发候选事件，不跑三候选")
    void simpleRoute_shouldGenerateOnceWithoutCandidateEvent() {
        stubSingleStepPlan(AgentRoute.SIMPLE);
        when(sqlAssistant.generateSql(
                eq(OBJECTIVE), eq(SCHEMA), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SHORT_SQL);

        String output = start(prepared());

        assertThat(output).doesNotContain("step.sql_candidates_evaluated");
        assertThat(output).contains("event: step.sql_ready", "event: approval.required");
        verify(sqlAssistant, org.mockito.Mockito.times(1)).generateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(sqlAssistant, never()).generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString());
        verify(repository).setStepSql(STEP_ID, SHORT_SQL, 1, 0);
        verify(executionService, never()).execute(any(), any(), anyLong(), anyLong());
    }

    // ------------------------------------------------------------------- 失败

    @Test
    @DisplayName("全部候选被 Guard 拒绝：运行失败，不创建待审批版本")
    void allCandidatesRejected_shouldFailRunWithoutApproval() {
        stubSingleStepPlan(AgentRoute.COMPLEX);
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenReturn(DANGEROUS_SQL, "DELETE FROM shop_orders", "SELECT id FROM other_db.shop_orders");

        String output = start(prepared());

        assertThat(output).contains("event: step.failed", "event: run.failed", "AGENT_STEP_FAILED");
        assertThat(output).doesNotContain("event: step.sql_ready", "event: approval.required");
        verify(repository, never()).setStepSql(any(), anyString(), anyInt(), anyInt());
        verify(approvalSessionStore, never()).register(any(), any(), anyInt(), anyString());
        verify(executionService, never()).execute(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("评分模型不可用：按规则选优并标记 ruleFallback，仍只产生一个待审批 SQL")
    void scoringUnavailable_shouldStillSelectByRules() {
        stubSingleStepPlan(AgentRoute.COMPLEX);
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenReturn(LONG_SQL, SHORT_SQL, DANGEROUS_SQL);
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenThrow(new com.queryforge.shared.error.ApiException(
                        com.queryforge.shared.error.ErrorCode.LLM_UPSTREAM_ERROR,
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "模型服务暂不可用"));

        String output = start(prepared());

        assertThat(output).contains("event: step.sql_candidates_evaluated", "\"ruleFallback\":true");
        assertThat(output.split("event: step.sql_ready", -1).length - 1).isEqualTo(1);
        // 纯规则下更短的 SQL 占优。
        verify(repository).setStepSql(STEP_ID, SHORT_SQL, 1, 0);
        verify(executionService, never()).execute(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("候选生成预算不足：已生成合法候选按规则选优，不越额、不伪造三候选")
    void budgetExhausted_shouldUseAlreadyGeneratedCandidates() {
        stubSingleStepPlan(AgentRoute.COMPLEX);
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenAnswer(invocation -> switch (invocation.<Integer>getArgument(7)) {
                    case 1 -> LONG_SQL;
                    case 2 -> SHORT_SQL;
                    default -> "SELECT amount FROM shop_orders";
                });
        // 预算只够 4 次：Manager + Planner + 两个候选；评分调用必拿不到额度 → 规则降级。
        when(repository.tryIncrementLlmCalls(eq(RUN_ID), anyInt())).thenReturn(1, 1, 1, 1, 0);

        String output = start(prepared());

        assertThat(output).contains("\"ruleFallback\":true");
        // 恰好两条候选进入选优（第三个没抢到额度），且只产生一个待审批 SQL。
        assertThat(output).contains("\"totalCandidates\":3", "\"validCandidates\":2");
        assertThat(output.split("event: step.sql_ready", -1).length - 1).isEqualTo(1);
        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        verify(repository).setStepSql(eq(STEP_ID), persisted.capture(), eq(1), eq(0));
        assertThat(persisted.getValue()).isIn(LONG_SQL, SHORT_SQL, "SELECT amount FROM shop_orders");
        verify(executionService, never()).execute(any(), any(), anyLong(), anyLong());
    }
}
