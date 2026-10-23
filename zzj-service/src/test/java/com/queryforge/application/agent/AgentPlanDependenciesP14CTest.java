package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.agent.roles.CodeAnalystAgent;
import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.agent.sql.SqlCandidateGenerator;
import com.queryforge.application.agent.sql.SqlCandidateScorer;
import com.queryforge.application.agent.sql.SqlCandidateSelectionService;
import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.application.memory.MemoryService;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.application.report.ReportService;
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
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.domain.model.LlmProperties;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.report.ReportResponse;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P14-C T01：计划步骤类型（角色/依赖）的服务端校验，以及合法计划按先后依赖推进。
 * 计划解析为纯函数式校验；推进顺序用真实引擎 + Mock 持久化验证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P14-C 计划角色与依赖")
class AgentPlanDependenciesP14CTest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final long CODE_STEP_ID = 12L;
    private static final long REPORT_STEP_ID = 13L;
    private static final long ANALYSIS_RUN_ID = 3L;
    private static final long REPORT_ID = 8L;
    private static final String QUESTION = "查询最近30天各地区销售额，分析变化趋势并生成报告";
    private static final String OBJECTIVE = "统计各地区销售额";
    private static final String CODE_OBJECTIVE = "分析销售额变化趋势";
    private static final String REPORT_OBJECTIVE = "生成数据分析报告";
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL, region VARCHAR)";
    private static final String DATABASE = "sql_demo_a";
    private static final String SQL = "SELECT region, SUM(amount) AS total FROM shop_orders GROUP BY region";
    private static final String PYTHON_CODE = "import json";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 9, 0);

    @Mock
    private LlmClient llmClient;
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
    private CodeAnalystAgent codeAnalyst;
    @Mock
    private ReportService reportService;
    @Mock
    private AgentReportComposer reportComposer;
    @Mock
    private AnalysisService analysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties(3, 2, 6000, 12);
    private AgentStepEngine engine;
    private AgentRunService service;

    @BeforeEach
    void setUp() {
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
        lenient().when(repository.findRun(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING, AgentRoute.COMPLEX.name())));
    }

    private AgentRun run(RunStatus status, String route) {
        return new AgentRun(
                RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "A 商店库", QUESTION,
                status, route, null, null, 0, null, null, null, NOW, NOW,
                null, null, null, null,
                Boolean.TRUE, 0, null, false, null, 0, null);
    }

    private AgentStep step(long id, int sequence, String objective, Integer dependsOn, StepStatus status,
                           String role, String sql, int sqlVersion, ApprovalStatus approval,
                           Long analysisRunId, Long reportId) {
        return new AgentStep(id, RUN_ID, sequence, objective, dependsOn, status, sql, sqlVersion,
                role, approval, 0, null, null, null, null, null, NOW, NOW, analysisRunId, reportId);
    }

    private static QueryResultResponse queryResult() {
        return new QueryResultResponse(
                List.of("region", "total"),
                List.of(Map.of("region", "华东", "total", 1200), Map.of("region", "华南", "total", 900)),
                2,
                false);
    }

    private static AnalysisRunResponse analysisResponse() {
        return new AnalysisRunResponse(
                ANALYSIS_RUN_ID, null, CONVERSATION_ID, CONNECTION_ID, RUN_ID, CODE_STEP_ID,
                "SandboxPython", "SUCCEEDED", 2, "bar",
                List.of(new ChartSchema.BarChart("各地区销售额", "地区", "销售额",
                        List.of("华东", "华南"), List.of(1200, 900), "run-5")),
                "销售额趋势已统计", null, null, 0, 820L, NOW.toString(), NOW.toString());
    }

    private static ReportResponse reportResponse() {
        return new ReportResponse(REPORT_ID, ANALYSIS_RUN_ID, CONNECTION_ID, RUN_ID,
                QUESTION, "SUCCEEDED", "<h1>报告</h1>", 128, 1, NOW.toString(), NOW.toString());
    }

    // ---------------------------------------------------------------- 计划解析校验

    @Test
    @DisplayName("合法计划保留角色与依赖（1-based，只能引用已出现步骤）")
    void parse_shouldKeepRolesAndDependencies() {
        AgentPlanner plannerUnderTest = new AgentPlanner(llmClient, objectMapper, properties);

        AgentPlan plan = plannerUnderTest.parse("""
                {"answerable": true, "steps": [
                  {"objective": "统计各地区销售额", "agent": "DATA_ANALYST"},
                  {"objective": "分析销售额变化趋势", "agent": "CODE_ANALYST", "dependsOn": 1},
                  {"objective": "生成数据分析报告", "agent": "REPORT_ASSISTANT", "dependsOn": 2}
                ]}
                """);

        assertThat(plan.steps()).hasSize(3);
        assertThat(plan.steps()).extracting(AgentPlan.Step::agent)
                .containsExactly("DATA_ANALYST", "CODE_ANALYST", "REPORT_ASSISTANT");
        assertThat(plan.steps()).extracting(AgentPlan.Step::dependsOn)
                .containsExactly(null, 1, 2);
    }

    @Test
    @DisplayName("未知角色拒绝整个计划")
    void parse_shouldRejectUnknownRole() {
        AgentPlanner plannerUnderTest = new AgentPlanner(llmClient, objectMapper, properties);

        assertThatThrownBy(() -> plannerUnderTest.parse("""
                {"answerable": true, "steps": [
                  {"objective": "统计销售额", "agent": "DATA_ANALYST"},
                  {"objective": "做点别的", "agent": "HACKER"}
                ]}
                """))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
    }

    @Test
    @DisplayName("自环、越界与前置引用依赖均被拒绝")
    void parse_shouldRejectInvalidDependencies() {
        AgentPlanner plannerUnderTest = new AgentPlanner(llmClient, objectMapper, properties);

        // 自环：第 2 步依赖自己。
        assertThatThrownBy(() -> plannerUnderTest.parse("""
                {"answerable": true, "steps": [
                  {"objective": "a", "agent": "DATA_ANALYST"},
                  {"objective": "b", "agent": "CODE_ANALYST", "dependsOn": 2}
                ]}
                """))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));

        // 越界：依赖不存在的第 9 步。
        assertThatThrownBy(() -> plannerUnderTest.parse("""
                {"answerable": true, "steps": [
                  {"objective": "a", "agent": "DATA_ANALYST"},
                  {"objective": "b", "agent": "CODE_ANALYST", "dependsOn": 9}
                ]}
                """))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));

        // 非整数依赖。
        assertThatThrownBy(() -> plannerUnderTest.parse("""
                {"answerable": true, "steps": [
                  {"objective": "a", "agent": "DATA_ANALYST"},
                  {"objective": "b", "agent": "CODE_ANALYST", "dependsOn": "one"}
                ]}
                """))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
    }

    // ---------------------------------------------------------------- 依赖顺序推进

    @Test
    @DisplayName("SQL → Code → Report 按依赖顺序推进，角色与依赖落库")
    void plan_shouldAdvanceInDependencyOrder() {
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.COMPLEX, "需要多步分析"));
        when(planner.plan(eq(QUESTION), eq(SCHEMA), anyString()))
                .thenReturn(new AgentPlan(true, "", List.of(
                        new AgentPlan.Step(OBJECTIVE, "DATA_ANALYST", null),
                        new AgentPlan.Step(CODE_OBJECTIVE, "CODE_ANALYST", 1),
                        new AgentPlan.Step(REPORT_OBJECTIVE, "REPORT_ASSISTANT", 2))));
        when(repository.insertStep(RUN_ID, 1, OBJECTIVE, null, "DATA_ANALYST")).thenReturn(STEP_ID);
        when(repository.insertStep(RUN_ID, 2, CODE_OBJECTIVE, 1, "CODE_ANALYST")).thenReturn(CODE_STEP_ID);
        when(repository.insertStep(RUN_ID, 3, REPORT_OBJECTIVE, 2, "REPORT_ASSISTANT"))
                .thenReturn(REPORT_STEP_ID);
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenReturn(SQL);
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenReturn("[{\"candidate\":1,\"score\":9}]");
        when(sqlAssistant.summarize(anyString(), anyString())).thenReturn("查询、分析与报告已完成");

        // DB 事实随落库推进：每完成一步，后续 findSteps 反映新的就绪集合。
        AtomicInteger completions = new AtomicInteger();
        when(repository.findSteps(RUN_ID)).thenAnswer(invocation -> switch (completions.get()) {
            case 0 -> List.of(
                    step(STEP_ID, 1, OBJECTIVE, null, StepStatus.PENDING, "DATA_ANALYST", null, 0,
                            ApprovalStatus.PENDING, null, null),
                    step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, "CODE_ANALYST", null, 0,
                            ApprovalStatus.PENDING, null, null),
                    step(REPORT_STEP_ID, 3, REPORT_OBJECTIVE, 2, StepStatus.PENDING, "REPORT_ASSISTANT",
                            null, 0, ApprovalStatus.PENDING, null, null));
            case 1 -> List.of(
                    step(STEP_ID, 1, OBJECTIVE, null, StepStatus.SUCCEEDED, "DATA_ANALYST", SQL, 1,
                            ApprovalStatus.APPROVED, null, null),
                    step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, "CODE_ANALYST", null, 0,
                            ApprovalStatus.PENDING, null, null),
                    step(REPORT_STEP_ID, 3, REPORT_OBJECTIVE, 2, StepStatus.PENDING, "REPORT_ASSISTANT",
                            null, 0, ApprovalStatus.PENDING, null, null));
            case 2 -> List.of(
                    step(STEP_ID, 1, OBJECTIVE, null, StepStatus.SUCCEEDED, "DATA_ANALYST", SQL, 1,
                            ApprovalStatus.APPROVED, null, null),
                    step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.SUCCEEDED, "CODE_ANALYST", null, 0,
                            ApprovalStatus.PENDING, ANALYSIS_RUN_ID, null),
                    step(REPORT_STEP_ID, 3, REPORT_OBJECTIVE, 2, StepStatus.PENDING, "REPORT_ASSISTANT",
                            null, 0, ApprovalStatus.PENDING, null, null));
            default -> List.of(
                    step(STEP_ID, 1, OBJECTIVE, null, StepStatus.SUCCEEDED, "DATA_ANALYST", SQL, 1,
                            ApprovalStatus.APPROVED, null, null),
                    step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.SUCCEEDED, "CODE_ANALYST", null, 0,
                            ApprovalStatus.PENDING, ANALYSIS_RUN_ID, null),
                    step(REPORT_STEP_ID, 3, REPORT_OBJECTIVE, 2, StepStatus.SUCCEEDED, "REPORT_ASSISTANT",
                            null, 0, ApprovalStatus.PENDING, null, REPORT_ID));
        });
        when(repository.completeStep(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyBoolean(), anyString()))
                .thenAnswer(invocation -> {
                    completions.incrementAndGet();
                    return 1;
                });
        when(repository.completeCodeStep(Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), anyString()))
                .thenAnswer(invocation -> {
                    completions.incrementAndGet();
                    return 1;
                });
        when(repository.completeReportStep(Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), anyString()))
                .thenAnswer(invocation -> {
                    completions.incrementAndGet();
                    return 1;
                });
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(queryResult());
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(repository.claimNonSqlStep(RUN_ID, REPORT_STEP_ID)).thenReturn(1);
        when(pythonAssistant.generateCode(eq(RUN_ID), eq(CODE_OBJECTIVE), anyString(), anyString(),
                anyString(), anyString())).thenReturn(PYTHON_CODE);
        when(codeAnalyst.analyzeApprovedResult(eq(USER_ID), any(), eq(PYTHON_CODE), any()))
                .thenReturn(analysisResponse());
        when(pythonAssistant.interpretResult(eq(RUN_ID), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("销售额整体呈上升趋势");
        when(analysisService.detail(USER_ID, ANALYSIS_RUN_ID)).thenReturn(analysisResponse());
        when(reportComposer.compose(eq(RUN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("整体销售额呈上升趋势");
        when(reportService.createForAgentRun(eq(USER_ID), eq(RUN_ID), eq(REPORT_STEP_ID),
                eq(List.of(ANALYSIS_RUN_ID)), anyString(), anyString()))
                .thenReturn(reportResponse());

        ByteArrayOutputStream planOut = new ByteArrayOutputStream();
        service.startRun(new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, DATABASE, QUESTION),
                planOut);
        String planOutput = planOut.toString(StandardCharsets.UTF_8);
        assertThat(planOutput).contains("event: approval.required");
        // 计划事件带出全部三个步骤与真实角色。
        assertThat(planOutput).contains("\"sequence\":1", "\"sequence\":2", "\"sequence\":3");
        assertThat(planOutput).contains("\"role\":\"REPORT_ASSISTANT\"");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.executeApproval(new PreparedApproval(
                USER_ID, run(RunStatus.WAITING_APPROVAL, AgentRoute.COMPLEX.name()),
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.WAITING_APPROVAL, "DATA_ANALYST", SQL, 1,
                        ApprovalStatus.PENDING, null, null),
                false, DATABASE, null), out);
        String output = out.toString(StandardCharsets.UTF_8);

        // 三步全部真实完成后才 run.completed。
        assertThat(output).contains("event: analysis.succeeded", "event: report.ready");
        assertThat(output).contains("event: run.completed");

        // 依赖顺序：先落 SQL，再 claim/完成 Code，最后 claim/完成 Report。
        var inOrder = inOrder(repository);
        inOrder.verify(repository).setStepSql(STEP_ID, SQL, 1, 0);
        inOrder.verify(repository).claimNonSqlStep(RUN_ID, CODE_STEP_ID);
        inOrder.verify(repository).completeCodeStep(eq(RUN_ID), eq(CODE_STEP_ID), eq(ANALYSIS_RUN_ID), anyString());
        inOrder.verify(repository).claimNonSqlStep(RUN_ID, REPORT_STEP_ID);
        inOrder.verify(repository).completeReportStep(eq(RUN_ID), eq(REPORT_STEP_ID), eq(REPORT_ID), anyString());
        // 角色与依赖在插入时落库。
        verify(repository).insertStep(RUN_ID, 2, CODE_OBJECTIVE, 1, "CODE_ANALYST");
        verify(repository).insertStep(RUN_ID, 3, REPORT_OBJECTIVE, 2, "REPORT_ASSISTANT");
    }
}
