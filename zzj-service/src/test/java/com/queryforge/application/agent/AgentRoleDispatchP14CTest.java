package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.queryforge.domain.model.LlmProperties;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.report.ReportResponse;
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
import java.util.Map;
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
 * P14-C T01/T06：三角色分发。同一计划内 SQL 步骤走 Guard/审批链，
 * CODE_ANALYST 步骤消费已审批真实行做隔离分析，REPORT_ASSISTANT 步骤基于真实工件生成报告；
 * 非 SQL 步骤不生成 SQL、不进 WAITING_APPROVAL、不发 approval.required。
 * 模型与持久化为 Mock，Guard/上下文装配/预算限制为真实组件。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P14-C 三角色分发")
class AgentRoleDispatchP14CTest {

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
    private static final String PYTHON_CODE = "import json\nprint(json.dumps({'charts': [], 'summary': 'ok'}))";
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
    private CodeAnalystAgent codeAnalyst;
    @Mock
    private ReportService reportService;
    @Mock
    private AgentReportComposer reportComposer;
    @Mock
    private AnalysisService analysisService;

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
        lenient().when(repository.findRun(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING, AgentRoute.COMPLEX.name())));
    }

    private PreparedRun prepared() {
        return new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, DATABASE, QUESTION);
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
                "SandboxPython", "SUCCEEDED", 2, "bar", List.of(barChart()), "销售额趋势已统计",
                null, null, 0, 820L, NOW.toString(), NOW.toString());
    }

    private static ChartSchema.BarChart barChart() {
        return new ChartSchema.BarChart("各地区销售额", "地区", "销售额",
                List.of("华东", "华南"), List.of(1200, 900), "run-" + RUN_ID);
    }

    private static ReportResponse reportResponse() {
        return new ReportResponse(REPORT_ID, ANALYSIS_RUN_ID, CONNECTION_ID, RUN_ID,
                QUESTION, "SUCCEEDED", "<h1>报告</h1>", 128, 1, NOW.toString(), NOW.toString());
    }

    /** COMPLEX 路由 + 两步计划（SQL → Code），返回计划阶段 SSE。 */
    private String startSqlThenCodePlan() {
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.COMPLEX, "需要多步分析"));
        when(planner.plan(eq(QUESTION), eq(SCHEMA), anyString()))
                .thenReturn(new AgentPlan(true, "", List.of(
                        new AgentPlan.Step(OBJECTIVE, "DATA_ANALYST", null),
                        new AgentPlan.Step(CODE_OBJECTIVE, "CODE_ANALYST", 1))));
        when(repository.insertStep(RUN_ID, 1, OBJECTIVE, null, "DATA_ANALYST")).thenReturn(STEP_ID);
        when(repository.insertStep(RUN_ID, 2, CODE_OBJECTIVE, 1, "CODE_ANALYST")).thenReturn(CODE_STEP_ID);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.PENDING, "DATA_ANALYST", null, 0,
                        ApprovalStatus.PENDING, null, null),
                step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, "CODE_ANALYST", null, 0,
                        ApprovalStatus.PENDING, null, null)));
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenReturn(SQL);
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenReturn("[{\"candidate\":1,\"score\":9}]");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.startRun(prepared(), output);
        return output.toString(StandardCharsets.UTF_8);
    }

    /** 审批后 DB 事实：SQL 步骤已成功，Code 步骤仍 PENDING（由本轮推进）。 */
    private void stubApprovedThenCompleted() {
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(queryResult());
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.SUCCEEDED, "DATA_ANALYST", SQL, 1,
                        ApprovalStatus.APPROVED, null, null),
                step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, "CODE_ANALYST", null, 0,
                        ApprovalStatus.PENDING, null, null)));
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(pythonAssistant.generateCode(eq(RUN_ID), eq(CODE_OBJECTIVE), anyString(), anyString(),
                anyString(), anyString())).thenReturn(PYTHON_CODE);
        when(codeAnalyst.analyzeApprovedResult(eq(USER_ID), any(), eq(PYTHON_CODE), any()))
                .thenReturn(analysisResponse());
        when(pythonAssistant.interpretResult(eq(RUN_ID), anyString(), anyString(), anyList(), anyString(), anyString()))
                .thenReturn("销售额整体呈上升趋势");
        when(repository.completeCodeStep(eq(RUN_ID), eq(CODE_STEP_ID), eq(ANALYSIS_RUN_ID), anyString())).thenReturn(1);
        when(sqlAssistant.summarize(anyString(), anyString())).thenReturn("查询与分析已完成");
    }

    private String approveSqlStep() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.executeApproval(new PreparedApproval(
                USER_ID, run(RunStatus.WAITING_APPROVAL, AgentRoute.COMPLEX.name()),
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.WAITING_APPROVAL, "DATA_ANALYST", SQL, 1,
                        ApprovalStatus.PENDING, null, null),
                false, DATABASE, null), output);
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Code 步骤消费已审批真实行做隔离分析，不生成 SQL、不进审批")
    void codeStep_shouldConsumeApprovedRowsWithoutGeneratingSql() {
        String planOutput = startSqlThenCodePlan();
        assertThat(planOutput).contains("event: approval.required", "\"role\":\"CODE_ANALYST\"");

        stubApprovedThenCompleted();
        String output = approveSqlStep();

        // Code 步骤真实生命周期事件。
        assertThat(output).contains("event: analysis.started", "event: analysis.succeeded");
        assertThat(output).contains("\"analysisRunId\":" + ANALYSIS_RUN_ID);
        assertThat(output).contains("event: step.succeeded", "event: run.completed");
        // 非 SQL 步骤不出现 SQL 审批语义。
        assertThat(output).doesNotContain("event: approval.required");
        assertThat(output).doesNotContain("event: step.sql_ready");
        // SQL 生成只发生在 SQL 步骤（三个候选一次生成模型），Code 步骤不再生成 SQL。
        verify(sqlAssistant, times(3)).generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString());
        verify(sqlAssistant, never()).generateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).setStepSql(eq(CODE_STEP_ID), any(), anyInt(), anyInt());
        // 真实行数据（同请求交接）进入沙箱分析。
        ArgumentCaptor<com.queryforge.application.agent.AuthorizedSqlInput> captor =
                ArgumentCaptor.forClass(com.queryforge.application.agent.AuthorizedSqlInput.class);
        verify(codeAnalyst).analyzeApprovedResult(eq(USER_ID), captor.capture(), eq(PYTHON_CODE), any());
        com.queryforge.application.agent.AuthorizedSqlInput input = captor.getValue();
        assertThat(input.result().rows()).hasSize(2);
        assertThat(input.result().columns()).containsExactly("region", "total");
        assertThat(input.userId()).isEqualTo(USER_ID);
        assertThat(input.runId()).isEqualTo(RUN_ID);
        assertThat(input.refetched()).isFalse();
    }

    @Test
    @DisplayName("Report 步骤基于真实工件生成报告，复用 ReportService 且不生成 SQL")
    void reportStep_shouldGenerateReportFromRealArtifacts() {
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
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.PENDING, "DATA_ANALYST", null, 0,
                        ApprovalStatus.PENDING, null, null),
                step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, "CODE_ANALYST", null, 0,
                        ApprovalStatus.PENDING, null, null),
                step(REPORT_STEP_ID, 3, REPORT_OBJECTIVE, 2, StepStatus.PENDING, "REPORT_ASSISTANT", null, 0,
                        ApprovalStatus.PENDING, null, null)));
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenReturn(SQL);
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenReturn("[{\"candidate\":1,\"score\":9}]");

        ByteArrayOutputStream planOut = new ByteArrayOutputStream();
        service.startRun(prepared(), planOut);
        assertThat(planOut.toString(StandardCharsets.UTF_8)).contains("event: approval.required");

        // 审批后：SQL 与 Code 均已成功，Report 步骤收集真实 analysisRunId。
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(queryResult());
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.SUCCEEDED, "DATA_ANALYST", SQL, 1,
                        ApprovalStatus.APPROVED, null, null),
                step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.SUCCEEDED, "CODE_ANALYST", null, 0,
                        ApprovalStatus.PENDING, ANALYSIS_RUN_ID, null),
                step(REPORT_STEP_ID, 3, REPORT_OBJECTIVE, 2, StepStatus.PENDING, "REPORT_ASSISTANT", null, 0,
                        ApprovalStatus.PENDING, null, null)));
        when(repository.claimNonSqlStep(RUN_ID, REPORT_STEP_ID)).thenReturn(1);
        when(analysisService.detail(USER_ID, ANALYSIS_RUN_ID)).thenReturn(analysisResponse());
        when(reportComposer.compose(eq(RUN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("整体销售额呈上升趋势");
        when(reportService.createForAgentRun(eq(USER_ID), eq(RUN_ID), eq(REPORT_STEP_ID),
                eq(List.of(ANALYSIS_RUN_ID)), anyString(), anyString()))
                .thenReturn(reportResponse());
        when(repository.completeReportStep(eq(RUN_ID), eq(REPORT_STEP_ID), eq(REPORT_ID), anyString())).thenReturn(1);
        when(sqlAssistant.summarize(anyString(), anyString())).thenReturn("报告已生成");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.executeApproval(new PreparedApproval(
                USER_ID, run(RunStatus.WAITING_APPROVAL, AgentRoute.COMPLEX.name()),
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.WAITING_APPROVAL, "DATA_ANALYST", SQL, 1,
                        ApprovalStatus.PENDING, null, null),
                false, DATABASE, null), out);
        String output = out.toString(StandardCharsets.UTF_8);

        assertThat(output).contains("event: report.ready", "\"reportId\":" + REPORT_ID);
        assertThat(output).contains("event: run.completed");
        assertThat(output).doesNotContain("event: step.sql_ready");
        // Report 步骤把同 Run 内真实成功的分析工件交给既有 ReportService。
        verify(reportService).createForAgentRun(eq(USER_ID), eq(RUN_ID), eq(REPORT_STEP_ID),
                eq(List.of(ANALYSIS_RUN_ID)), anyString(), anyString());
        // SQL 生成仍然只属于 SQL 步骤。
        verify(sqlAssistant, times(3)).generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("显式 Code 步骤没有上游取数步骤：拒绝计划，不伪造数据")
    void explicitCodeStepWithoutUpstream_shouldBeRejected() {
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.COMPLEX, "需要多步分析"));
        when(planner.plan(eq(QUESTION), eq(SCHEMA), anyString()))
                .thenReturn(new AgentPlan(true, "", List.of(
                        new AgentPlan.Step(CODE_OBJECTIVE, "CODE_ANALYST", null))));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.startRun(prepared(), output);
        String text = output.toString(StandardCharsets.UTF_8);

        assertThat(text).contains("event: run.failed", "AGENT_PLAN_INVALID");
        verify(repository, never()).claimNonSqlStep(anyLong(), anyLong());
        verify(codeAnalyst, never()).analyzeApprovedResult(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Python 生成预算耗尽：步骤真实失败，不伪造分析完成")
    void pythonBudgetExhausted_shouldFailWithoutFabrication() {
        startSqlThenCodePlan();
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(queryResult());
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.SUCCEEDED, "DATA_ANALYST", SQL, 1,
                        ApprovalStatus.APPROVED, null, null),
                step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, "CODE_ANALYST", null, 0,
                        ApprovalStatus.PENDING, null, null)));
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        // 预算只够 Planner 之前的调用：Python 生成拿不到额度。
        when(repository.tryIncrementLlmCalls(anyLong(), anyInt())).thenReturn(0);

        String output = approveSqlStep();

        assertThat(output).contains("event: step.failed", "event: run.failed");
        assertThat(output).doesNotContain("event: analysis.succeeded");
        assertThat(output).doesNotContain("event: run.completed");
        verify(codeAnalyst, never()).analyzeApprovedResult(any(), any(), any(), any());
        verify(repository, never()).completeCodeStep(anyLong(), anyLong(), any(), anyString());
    }
}
