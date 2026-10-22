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
import com.queryforge.domain.agent.AgentStepResponse;
import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.ChartSchema;
import com.queryforge.domain.model.LlmProperties;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * P14-C T04：非 SQL 步骤的持久化与 CAS 幂等。
 * claim/complete/fail 都以「受影响行数」为唯一事实：领取冲突只回放既有事实不二次执行；
 * 迟到完成不覆写终态也不冒充成功；终态失败不改写。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P14-C 非 SQL 步骤持久化与幂等")
class AgentNonSqlStepPersistenceP14CTest {

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final long CODE_STEP_ID = 12L;
    private static final long ANALYSIS_RUN_ID = 3L;
    private static final String QUESTION = "查询各地区销售额并分析趋势";
    private static final String CODE_OBJECTIVE = "分析销售额趋势";
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
        SqlCandidateGenerator generator = new SqlCandidateGenerator(
                sqlAssistant, llmLimiter,
                new LlmProperties("https://example.invalid/v1", "key", "model",
                        Duration.ofSeconds(5), Duration.ofSeconds(5)));
        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, new ReadOnlySqlGuard(), properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler,
                planState, roleRecorder, llmLimiter, toolAssistant, toolPolicy, toolOutputSanitizer,
                new SqlCandidateSelectionService(sqlAssistant, llmLimiter, generator,
                        new SqlCandidateScorer(new ReadOnlySqlGuard(), objectMapper)),
                pythonAssistant, codeAnalyst, reportService, reportComposer, analysisService);

        lenient().when(repository.tryIncrementLlmCalls(anyLong(), anyInt())).thenReturn(1);
        lenient().when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn("shop_orders(...)");
        lenient().when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        lenient().when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        lenient().when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        lenient().when(roleAssigner.assign(anyString())).thenReturn(AgentRole.DATA_ANALYST);
        lenient().when(repository.findRun(RUN_ID)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        lenient().when(pythonAssistant.generateCode(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(PYTHON_CODE);
        lenient().when(codeAnalyst.analyzeApprovedResult(eq(USER_ID), any(), eq(PYTHON_CODE), any()))
                .thenReturn(analysisResponse());
        lenient().when(pythonAssistant.interpretResult(anyLong(), anyString(), anyString(), any(),
                anyString(), anyString())).thenReturn("销售额集中在华东");
        lenient().when(repository.findSteps(RUN_ID)).thenReturn(List.of(codeStep(StepStatus.PENDING)));
    }

    private AgentRun run(RunStatus status) {
        return new AgentRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "A 商店库", QUESTION,
                status, "COMPLEX", null, null, 0, null, null, null, NOW, NOW,
                null, null, null, null);
    }

    private AgentStep codeStep(StepStatus status) {
        return codeStep(status, null, null, null);
    }

    private AgentStep codeStep(StepStatus status, String errorCode, Long analysisRunId, String summary) {
        return new AgentStep(CODE_STEP_ID, RUN_ID, 2, CODE_OBJECTIVE, 1, status, null, 0,
                "CODE_ANALYST", ApprovalStatus.PENDING, 0, null, null, errorCode,
                errorCode == null ? null : "分析失败", summary, NOW, NOW, analysisRunId, null);
    }

    private static AnalysisRunResponse analysisResponse() {
        return new AnalysisRunResponse(ANALYSIS_RUN_ID, null, CONVERSATION_ID, CONNECTION_ID, RUN_ID,
                CODE_STEP_ID, "SandboxPython", "SUCCEEDED", 2, "bar",
                List.of(new ChartSchema.BarChart("各地区销售额", "地区", "销售额",
                        List.of("华东"), List.of(1200), "run-5")),
                "销售额趋势已统计", null, null, 0, 820L, NOW.toString(), NOW.toString());
    }

    private static QueryResultResponse queryResult() {
        return new QueryResultResponse(List.of("region", "total"),
                List.of(Map.of("region", "华东", "total", 1200)), 1, false);
    }

    private String advance() throws IOException {
        return advance(AgentStepExecutionInputs.empty());
    }

    private String advance(AgentStepExecutionInputs inputs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        engine.advanceReadyStep(
                new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, DATABASE, QUESTION),
                codeStep(StepStatus.PENDING), inputs, "shop_orders(...)",
                new SseEventWriter(out, objectMapper), null);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("领取冲突（claim=0）：只回放既有事实，不二次执行沙箱")
    void claimConflict_shouldReplayOnly() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(0);
        when(repository.findStep(RUN_ID, CODE_STEP_ID))
                .thenReturn(Optional.of(codeStep(StepStatus.SUCCEEDED, null, ANALYSIS_RUN_ID, "已完成分析")));

        String output = advance();

        verify(codeAnalyst, never()).analyzeApprovedResult(any(), any(), any(), any());
        verify(pythonAssistant, never()).generateCode(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString());
        assertThat(output).contains("event: step.succeeded", "已完成分析");
        assertThat(output).doesNotContain("event: analysis.succeeded");
    }

    @Test
    @DisplayName("已失败步骤被重复推进：回放失败事实，不重新执行")
    void claimConflict_failedStep_shouldReplayFailure() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(0);
        when(repository.findStep(RUN_ID, CODE_STEP_ID))
                .thenReturn(Optional.of(codeStep(StepStatus.FAILED, "ANALYSIS_EXECUTION_ERROR", null, null)));

        String output = advance();

        verify(codeAnalyst, never()).analyzeApprovedResult(any(), any(), any(), any());
        assertThat(output).contains("event: step.failed", "ANALYSIS_EXECUTION_ERROR");
    }

    @Test
    @DisplayName("迟到完成（complete=0）：不发成功事件，标记状态冲突")
    void lateCompletion_shouldNotClaimSuccess() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(repository.completeCodeStep(eq(RUN_ID), eq(CODE_STEP_ID), eq(ANALYSIS_RUN_ID), anyString()))
                .thenReturn(0);
        ApprovedSqlOutcome outcome = new ApprovedSqlOutcome(
                RUN_ID, STEP_ID, 1, USER_ID, CONNECTION_ID, CONVERSATION_ID, DATABASE, queryResult());

        String output = advance(AgentStepExecutionInputs.fromOutcome(outcome, null, CONVERSATION_ID, ""));

        verify(codeAnalyst).analyzeApprovedResult(eq(USER_ID), any(), eq(PYTHON_CODE), any());
        assertThat(output).contains("event: analysis.failed", "AGENT_STATE_CONFLICT");
        assertThat(output).doesNotContain("event: analysis.succeeded");
        assertThat(output).doesNotContain("event: step.succeeded");
    }

    @Test
    @DisplayName("完成/失败的 CAS 都以受影响行数为唯一事实")
    void casMethods_shouldUseAffectedRows() throws IOException {
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(repository.failNonSqlStep(eq(RUN_ID), eq(CODE_STEP_ID), anyString(), anyString())).thenReturn(0);

        // 没有任何可信上游数据 → 走失败路径；但 fail CAS 返回 0（步骤已终态）→ 不改写事实。
        String output = advance();

        verify(repository).failNonSqlStep(eq(RUN_ID), eq(CODE_STEP_ID), anyString(), anyString());
        verify(repository, never()).updateRunStatus(eq(RUN_ID), eq(RunStatus.FAILED), any(), anyString(),
                anyString());
        assertThat(output).doesNotContain("event: step.failed");
    }

    @Test
    @DisplayName("步骤响应携带真实工件 ID（analysisRunId/reportId）")
    void stepResponse_shouldCarryArtifactIds() {
        AgentStepResponse response = new AgentStepResponse(
                CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.SUCCEEDED, null, 0,
                "CODE_ANALYST", ApprovalStatus.PENDING, 0, 2, false,
                null, null, "分析完成", ANALYSIS_RUN_ID, 88L);

        assertThat(response.analysisRunId()).isEqualTo(ANALYSIS_RUN_ID);
        assertThat(response.reportId()).isEqualTo(88L);
    }

    @Test
    @DisplayName("迁移脚本为 agent_steps 增加 analysis_run_id/report_id 且可重复执行")
    void migration_shouldAddArtifactColumns() throws IOException {
        String sql = new String(getClass().getResourceAsStream("/db/15-p14c-agent-step-artifacts.sql")
                .readAllBytes(), StandardCharsets.UTF_8);

        assertThat(sql).contains("analysis_run_id", "report_id", "agent_steps");
        assertThat(sql).contains("information_schema");
    }
}
