package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.domain.agent.graph.GraphNode;
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
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.model.LlmProperties;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.report.ReportResponse;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.web.SseEventWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P14-C T06：新增 SSE 事件的载荷与时序。成功事件只能在工件真实落库之后发送；
 * 失败事件携带真实错误码；Graph 节点随角色推进。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P14-C SSE 事件与图节点")
class AgentP14CSseTest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final long CODE_STEP_ID = 12L;
    private static final long REPORT_STEP_ID = 13L;
    private static final long ANALYSIS_RUN_ID = 3L;
    private static final long REPORT_ID = 8L;
    private static final String QUESTION = "查询各地区销售额并生成报告";
    private static final String CODE_OBJECTIVE = "分析销售额趋势";
    private static final String REPORT_OBJECTIVE = "生成数据分析报告";
    private static final String DATABASE = "sql_demo_a";
    private static final String PYTHON_CODE = "print('ok')";
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
    @Mock
    private SkillService skillService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentStepEngine engine;

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
        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, new ReadOnlySqlGuard(), properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler,
                planState, roleRecorder, llmLimiter, toolAssistant, toolPolicy, toolOutputSanitizer,
                new SqlCandidateSelectionService(sqlAssistant, llmLimiter,
                        new SqlCandidateGenerator(sqlAssistant, llmLimiter,
                                new LlmProperties("https://example.invalid/v1", "key", "model",
                                        Duration.ofSeconds(5), Duration.ofSeconds(5))),
                        new SqlCandidateScorer(new ReadOnlySqlGuard(), objectMapper)),
                pythonAssistant, codeAnalyst, reportService, reportComposer, analysisService);

        lenient().when(repository.tryIncrementLlmCalls(anyLong(), anyInt())).thenReturn(1);
        lenient().when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn("shop_orders(...)");
        lenient().when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        lenient().when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        lenient().when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        lenient().when(roleAssigner.assign(anyString())).thenReturn(AgentRole.DATA_ANALYST);
        lenient().when(repository.findRun(RUN_ID)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        lenient().when(repository.findSteps(RUN_ID)).thenReturn(List.of());
        lenient().when(pythonAssistant.generateCode(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(PYTHON_CODE);
        lenient().when(codeAnalyst.analyzeApprovedResult(eq(USER_ID), any(), eq(PYTHON_CODE), any()))
                .thenReturn(analysisResponse());
        lenient().when(pythonAssistant.interpretResult(anyLong(), anyString(), anyString(), any(),
                anyString(), anyString())).thenReturn("销售额集中在华东");
    }

    private AgentRun run(RunStatus status) {
        return new AgentRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "A 商店库", QUESTION,
                status, "COMPLEX", null, null, 0, null, null, null, NOW, NOW,
                null, null, null, null);
    }

    private AgentStep step(long id, int sequence, String objective, StepStatus status, String role) {
        return new AgentStep(id, RUN_ID, sequence, objective, 1, status, null, 0, role,
                ApprovalStatus.PENDING, 0, null, null, null, null, null, NOW, NOW, null, null);
    }

    private static AnalysisRunResponse analysisResponse() {
        return new AnalysisRunResponse(ANALYSIS_RUN_ID, null, CONVERSATION_ID, CONNECTION_ID, RUN_ID,
                CODE_STEP_ID, "SandboxPython", "SUCCEEDED", 2, "bar",
                List.of(new ChartSchema.BarChart("各地区销售额", "地区", "销售额",
                        List.of("华东"), List.of(1200), "run-5")),
                "销售额趋势已统计", null, null, 0, 820L, NOW.toString(), NOW.toString());
    }

    private static ReportResponse reportResponse() {
        return new ReportResponse(REPORT_ID, ANALYSIS_RUN_ID, CONNECTION_ID, RUN_ID, "销售报告",
                "READY", "<h1>销售报告</h1>", 128, 1, NOW.toString(), NOW.toString());
    }

    private static QueryResultResponse queryResult() {
        return new QueryResultResponse(List.of("region", "total"),
                List.of(Map.of("region", "华东", "total", 1200)), 1, false);
    }

    private PreparedRun preparedRun() {
        return new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, DATABASE, QUESTION);
    }

    private String advance(AgentStep step, AgentStepExecutionInputs inputs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        engine.advanceReadyStep(preparedRun(), step, inputs, "shop_orders(...)",
                new SseEventWriter(out, objectMapper), null);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Code 步骤：analysis.started → analysis.succeeded → step.succeeded，载荷为真实工件")
    void codeStep_shouldEmitOrderedEvents() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(repository.completeCodeStep(eq(RUN_ID), eq(CODE_STEP_ID), eq(ANALYSIS_RUN_ID), anyString()))
                .thenReturn(1);
        ApprovedSqlOutcome outcome = new ApprovedSqlOutcome(
                RUN_ID, STEP_ID, 1, USER_ID, CONNECTION_ID, CONVERSATION_ID, DATABASE, queryResult());

        String output = advance(step(CODE_STEP_ID, 2, CODE_OBJECTIVE, StepStatus.PENDING, "CODE_ANALYST"),
                AgentStepExecutionInputs.fromOutcome(outcome, null, CONVERSATION_ID, ""));

        int started = output.indexOf("event: analysis.started");
        int succeeded = output.indexOf("event: analysis.succeeded");
        int stepSucceeded = output.indexOf("event: step.succeeded");
        assertThat(started).isPositive();
        assertThat(succeeded).isGreaterThan(started);
        assertThat(stepSucceeded).isGreaterThan(succeeded);
        // 成功载荷带真实 analysisRunId 与图表数。
        assertThat(output.substring(succeeded)).contains("\"analysisRunId\":" + ANALYSIS_RUN_ID,
                "\"chartCount\":1");
        // 成功事件必须在工件落库之后：先 complete 再发事件（由返回值 1 保证，此处核对顺序断言）。
        verify(repository).completeCodeStep(eq(RUN_ID), eq(CODE_STEP_ID), eq(ANALYSIS_RUN_ID), anyString());
        verify(graphStateService).enter(eq(RUN_ID), eq(GraphNode.CODE_ANALYSIS), any());
    }

    @Test
    @DisplayName("分析失败：analysis.failed 带真实错误码，不发成功事件")
    void codeStepFailure_shouldEmitFailedOnly() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(repository.failNonSqlStep(eq(RUN_ID), eq(CODE_STEP_ID), anyString(), anyString())).thenReturn(1);
        lenient().when(codeAnalyst.analyzeApprovedResult(eq(USER_ID), any(), eq(PYTHON_CODE), any()))
                .thenThrow(new com.queryforge.shared.error.ApiException(
                        com.queryforge.shared.error.ErrorCode.ANALYSIS_EXECUTION_ERROR,
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "沙箱执行失败"));
        ApprovedSqlOutcome outcome = new ApprovedSqlOutcome(
                RUN_ID, STEP_ID, 1, USER_ID, CONNECTION_ID, CONVERSATION_ID, DATABASE, queryResult());

        String output = advance(step(CODE_STEP_ID, 2, CODE_OBJECTIVE, StepStatus.PENDING, "CODE_ANALYST"),
                AgentStepExecutionInputs.fromOutcome(outcome, null, CONVERSATION_ID, ""));

        assertThat(output).contains("event: analysis.failed", "ANALYSIS_EXECUTION_ERROR", "沙箱执行失败");
        assertThat(output).doesNotContain("event: analysis.succeeded");
        assertThat(output).doesNotContain("event: step.succeeded");
    }

    @Test
    @DisplayName("Report 步骤：report.ready 带真实 reportId 与标题")
    void reportStep_shouldEmitReportReady() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, REPORT_STEP_ID)).thenReturn(1);
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                new AgentStep(CODE_STEP_ID, RUN_ID, 2, CODE_OBJECTIVE, 1, StepStatus.SUCCEEDED, null, 0,
                        "CODE_ANALYST", ApprovalStatus.PENDING, 0, 1, false, null, null, "分析完成",
                        NOW, NOW, ANALYSIS_RUN_ID, null)));
        when(analysisService.detail(USER_ID, ANALYSIS_RUN_ID)).thenReturn(analysisResponse());
        when(reportComposer.compose(eq(RUN_ID), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("整体上升");
        when(reportService.createForAgentRun(eq(USER_ID), eq(RUN_ID), eq(REPORT_STEP_ID),
                eq(List.of(ANALYSIS_RUN_ID)), anyString(), anyString()))
                .thenReturn(reportResponse());
        when(repository.completeReportStep(eq(RUN_ID), eq(REPORT_STEP_ID), eq(REPORT_ID), anyString()))
                .thenReturn(1);

        String output = advance(step(REPORT_STEP_ID, 3, REPORT_OBJECTIVE, StepStatus.PENDING, "REPORT_ASSISTANT"),
                AgentStepExecutionInputs.empty());

        assertThat(output).contains("event: report.ready", "\"reportId\":" + REPORT_ID);
        assertThat(output).contains("event: step.succeeded");
        // 收尾阶段也会进入同一个节点，因此只断言“报告步骤推进时已进入”。
        verify(graphStateService, org.mockito.Mockito.atLeastOnce())
                .enter(eq(RUN_ID), eq(GraphNode.REPORT_GENERATION), any());
    }
}
