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

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P14-C T02：已审批 SQL 的真实数据交接与跨请求恢复。
 * 同请求交接不做二次取数；跨请求只按“已审批、未修改的原样 SQL”重新取数并标记 refetched；
 * 上游未审批/无 SQL 时明确失败，绝不用空数组冒充。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P14-C 已审批 SQL 数据交接")
class ApprovedSqlHandoffP14CTest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final long CODE_STEP_ID = 12L;
    private static final long ANALYSIS_RUN_ID = 3L;
    private static final String QUESTION = "查询各地区销售额并分析趋势";
    private static final String OBJECTIVE = "统计各地区销售额";
    private static final String CODE_OBJECTIVE = "分析销售额趋势";
    private static final String DATABASE = "sql_demo_a";
    private static final String SQL = "SELECT region, SUM(amount) AS total FROM shop_orders GROUP BY region";
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
        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, new ReadOnlySqlGuard(), properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler,
                planState, roleRecorder, llmLimiter, toolAssistant, toolPolicy, toolOutputSanitizer,
                new SqlCandidateSelectionService(sqlAssistant, llmLimiter, generator, scorer),
                pythonAssistant, codeAnalyst, reportService, reportComposer, analysisService);

        lenient().when(repository.tryIncrementLlmCalls(anyLong(), anyInt())).thenReturn(1);
        lenient().when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn("shop_orders(...)");
        lenient().when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        lenient().when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        lenient().when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        lenient().when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
        lenient().when(roleAssigner.assign(anyString())).thenReturn(AgentRole.DATA_ANALYST);
        lenient().when(repository.findRun(RUN_ID))
                .thenReturn(Optional.of(run(RunStatus.RUNNING)));
        lenient().when(pythonAssistant.generateCode(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(PYTHON_CODE);
        lenient().when(codeAnalyst.analyzeApprovedResult(eq(USER_ID), any(), eq(PYTHON_CODE), any()))
                .thenReturn(analysisResponse());
        lenient().when(pythonAssistant.interpretResult(anyLong(), anyString(), anyString(), any(),
                anyString(), anyString())).thenReturn("销售额集中在华东");
        lenient().when(repository.completeCodeStep(anyLong(), anyLong(), any(), anyString())).thenReturn(1);
        lenient().when(repository.failNonSqlStep(eq(RUN_ID), eq(CODE_STEP_ID), anyString(), anyString()))
                .thenReturn(1);
    }

    private PreparedRun preparedRun() {
        return new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, DATABASE, QUESTION);
    }

    private AgentRun run(RunStatus status) {
        return new AgentRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "A 商店库", QUESTION,
                status, "COMPLEX", null, null, 0, null, null, null, NOW, NOW,
                null, null, null, null,
                Boolean.TRUE, 0, null, false, null, 0, null);
    }

    private AgentStep codeStep() {
        return new AgentStep(CODE_STEP_ID, RUN_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, null, 0,
                "CODE_ANALYST", ApprovalStatus.PENDING, 0, null, null, null, null, null,
                NOW, NOW, null, null);
    }

    private AgentStep sqlStep(StepStatus status, ApprovalStatus approval) {
        return new AgentStep(STEP_ID, RUN_ID, 1, OBJECTIVE, null, status, SQL, 1,
                "DATA_ANALYST", approval, 0, null, null, null, null, null,
                NOW, NOW, null, null);
    }

    private static QueryResultResponse queryResult() {
        return new QueryResultResponse(List.of("region", "total"),
                List.of(Map.of("region", "华东", "total", 1200), Map.of("region", "华南", "total", 900)),
                2, false);
    }

    private static AnalysisRunResponse analysisResponse() {
        return new AnalysisRunResponse(ANALYSIS_RUN_ID, null, CONVERSATION_ID, CONNECTION_ID, RUN_ID,
                CODE_STEP_ID, "SandboxPython", "SUCCEEDED", 2, "bar",
                List.of(new ChartSchema.BarChart("各地区销售额", "地区", "销售额",
                        List.of("华东"), List.of(1200), "run-5")),
                "销售额趋势已统计", null, null, 0, 820L, NOW.toString(), NOW.toString());
    }

    private String advance(AgentStepExecutionInputs inputs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        engine.advanceReadyStep(preparedRun(), codeStep(), inputs, "shop_orders(...)",
                new SseEventWriter(out, objectMapper), null);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("同请求交接：直接用审批结果行，不重复取数、refetched=false")
    void inMemoryHandoff_shouldNotRefetch() throws IOException {
        ApprovedSqlOutcome outcome = new ApprovedSqlOutcome(
                RUN_ID, STEP_ID, 1, USER_ID, CONNECTION_ID, CONVERSATION_ID, DATABASE, queryResult());
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);

        String output = advance(AgentStepExecutionInputs.fromOutcome(outcome, null, CONVERSATION_ID, ""));

        verify(executionService, never()).execute(eq(USER_ID), any(ExecuteSqlRequest.class), anyLong(), anyLong());
        ArgumentCaptor<AuthorizedSqlInput> input = ArgumentCaptor.forClass(AuthorizedSqlInput.class);
        verify(codeAnalyst).analyzeApprovedResult(eq(USER_ID), input.capture(), eq(PYTHON_CODE), any());
        assertThat(input.getValue().refetched()).isFalse();
        assertThat(input.getValue().result().rows()).hasSize(2);
        assertThat(input.getValue().runId()).isEqualTo(RUN_ID);
        assertThat(input.getValue().stepId()).isEqualTo(STEP_ID);
        assertThat(output).contains("event: analysis.succeeded");
    }

    @Test
    @DisplayName("跨请求恢复：按已审批原样 SQL 重新取数，refetched=true 且来源可追溯")
    void crossRequestRecovery_shouldRefetchApprovedSqlOnly() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(repository.findStep(RUN_ID, STEP_ID)).thenReturn(Optional.of(sqlStep(StepStatus.SUCCEEDED,
                ApprovalStatus.APPROVED)));
        QueryResultResponse refreshed = new QueryResultResponse(List.of("region", "total"),
                List.of(Map.of("region", "华东", "total", 1500)), 1, false);
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(refreshed);

        String output = advance(new AgentStepExecutionInputs(RUN_ID, USER_ID, null, CONVERSATION_ID,
                CONNECTION_ID, STEP_ID, null, null, RUN_ID + "/" + STEP_ID, ""));

        ArgumentCaptor<ExecuteSqlRequest> request = ArgumentCaptor.forClass(ExecuteSqlRequest.class);
        verify(executionService).execute(eq(USER_ID), request.capture(), eq(RUN_ID), eq(STEP_ID));
        // 重新执行的必须是已审批的原样 SQL，不是任何模型新生成的语句。
        assertThat(request.getValue().sql()).isEqualTo(SQL);
        ArgumentCaptor<AuthorizedSqlInput> input = ArgumentCaptor.forClass(AuthorizedSqlInput.class);
        verify(codeAnalyst).analyzeApprovedResult(eq(USER_ID), input.capture(), eq(PYTHON_CODE), any());
        assertThat(input.getValue().refetched()).isTrue();
        assertThat(input.getValue().sourceSql()).isEqualTo(SQL);
        assertThat(input.getValue().result().rows()).hasSize(1);
        assertThat(output).contains("event: analysis.succeeded");
    }

    @Test
    @DisplayName("上游未审批：拒绝取数与执行，步骤明确失败")
    void unapprovedUpstream_shouldFailWithoutExecution() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(repository.findStep(RUN_ID, STEP_ID)).thenReturn(Optional.of(sqlStep(StepStatus.SUCCEEDED,
                ApprovalStatus.PENDING)));

        String output = advance(new AgentStepExecutionInputs(RUN_ID, USER_ID, null, CONVERSATION_ID,
                CONNECTION_ID, STEP_ID, null, null, RUN_ID + "/" + STEP_ID, ""));

        verify(executionService, never()).execute(eq(USER_ID), any(ExecuteSqlRequest.class), anyLong(), anyLong());
        verify(codeAnalyst, never()).analyzeApprovedResult(any(), any(), any(), any());
        assertThat(output).contains("event: step.failed");
        assertThat(output).doesNotContain("event: analysis.succeeded");
    }

    @Test
    @DisplayName("既无交接数据也无上游步骤：拒绝执行，不用空数组冒充")
    void missingUpstream_shouldFail() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);

        String output = advance(AgentStepExecutionInputs.empty());

        verify(codeAnalyst, never()).analyzeApprovedResult(any(), any(), any(), any());
        verify(repository, never()).completeCodeStep(anyLong(), anyLong(), any(), anyString());
        assertThat(output).contains("event: step.failed");
    }
}
