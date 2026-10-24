package com.queryforge.application.agent;

import com.queryforge.application.agent.runtime.AgentGraphRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.agent.roles.CodeAnalystAgent;
import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.domain.agent.roles.RoleRegistry;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.agent.sql.SqlCandidateGenerator;
import com.queryforge.application.agent.sql.SqlCandidateScorer;
import com.queryforge.application.agent.sql.SqlCandidateSelectionService;
import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.application.analysis.BuiltinAnalyzer;
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
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.domain.agent.AgentStep;
import com.queryforge.domain.agent.ApprovalStatus;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.roles.RoleProperties;
import com.queryforge.domain.analysis.AnalysisProperties;
import com.queryforge.domain.analysis.AnalysisStatus;
import com.queryforge.infrastructure.persistence.AnalysisRepository;
import com.queryforge.domain.model.LlmProperties;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.report.ReportResponse;
import com.queryforge.infrastructure.analysis.SandboxPythonEngine;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzj.sandbox.DockerPythonSandboxExecutor;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
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
 * P14-C 主链级真实沙箱闭环：{@link AgentStepEngine} 的 CODE_ANALYST 步骤真实穿过
 * {@link CodeAnalystAgent} → {@link AnalysisService} → 真实 Docker 容器执行模型 Python 代码。
 *
 * <p>除持久化与模型生成（LLM）为 Mock 外，沙箱执行、角色门禁、工件解析、图表校验、步骤持久化（CAS）
 * 全部真实。验证编排层在真实沙箱下能正确推进：审批后的真实行被交给容器、产出合法 output.json、
 * 图表严格校验通过、工件以 {@code {"charts", "summary"}} 包装、{@code completeCodeStep} 仅在
 * 真实成功后调用（runId 来自真实分析）。
 *
 * <p>Docker 守护进程不可用时如实跳过，不用桩冒充真实执行结论。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P14-C 主链真实沙箱闭环")
class AgentCodeStepRealP14CTest {

    @Mock
    private AgentGraphRunner graphRunner;

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final long CODE_STEP_ID = 12L;
    private static final long ANALYSIS_RUN_ID = 9001L;
    private static final String QUESTION = "查询各地区销售额，分析变化趋势";
    private static final String OBJECTIVE = "统计各地区销售额";
    private static final String CODE_OBJECTIVE = "分析销售额变化趋势";
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL, region VARCHAR)";
    private static final String DATABASE = "sql_demo_a";
    private static final String SQL = "SELECT region, SUM(amount) AS total FROM shop_orders GROUP BY region";
    private static final String IMAGE = "python:3.12-slim";
    private static final String PYTHON_CODE = """
            import json
            payload = json.load(open('/work/input.json'))
            rows = payload['rows']
            agg = {}
            for row in rows:
                agg[row['region']] = agg.get(row['region'], 0) + float(row['total'])
            cats = sorted(agg)
            out = {'charts': [{'chartType': 'bar', 'title': '地区销售额', 'xLabel': '地区',
                              'yLabel': '销售额', 'categories': cats,
                              'values': [agg[c] for c in cats], 'sourceRunId': '9001'}],
                   'summary': '真实沙箱E2E：合计 %.0f' % sum(agg.values())}
            open('/output/output.json', 'w').write(json.dumps(out, ensure_ascii=False))
            """;
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
    private ReportService reportService;
    @Mock
    private AgentReportComposer reportComposer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentStepEngine engine;
    private AgentRunService service;
    private AnalysisRepository analysisRepository;

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

        // 真实沙箱分析链路：角色门禁 + AnalysisService + 真实 Docker 容器。仅 Analysis 存储层为 Mock。
        analysisRepository = mock(AnalysisRepository.class);
        lenient().when(analysisRepository.insertRun(any(), any(), any(), any(), any(), any(), any(),
                any(), anyInt(), anyInt())).thenReturn(ANALYSIS_RUN_ID);
        lenient().when(analysisRepository.findOwned(anyLong(), anyLong())).thenReturn(Optional.of(
                new AnalysisRepository.AnalysisRun(ANALYSIS_RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID,
                        RUN_ID, CODE_STEP_ID, 55L, "SELECT region, total FROM t", "SandboxPython",
                        AnalysisStatus.SUCCEEDED, 2, 256 * 1024, null, null, "", "", 0, 0,
                        LocalDateTime.now(), LocalDateTime.now())));
        RoleRegistry registry = new RoleRegistry(new RoleProperties(Map.of(), Map.of()));
        SandboxPythonEngine sandboxEngine = new SandboxPythonEngine(
                new AnalysisProperties(200, 256 * 1024, 4000, 20, 256L * 1024 * 1024, 1.0, IMAGE, "python"),
                objectMapper,
                new DockerPythonSandboxExecutor());
        Assumptions.assumeTrue(sandboxEngine.available(), "仅在 Docker 守护进程可用时执行真实容器验证");
        AnalysisService realAnalysisService = new AnalysisService(
                analysisRepository,
                mock(com.queryforge.application.query.QueryHistoryService.class),
                mock(SqlExecutionService.class),
                mock(BuiltinAnalyzer.class),
                sandboxEngine,
                new AnalysisProperties(200, 256 * 1024, 4000, 20, 256L * 1024 * 1024, 1.0, IMAGE, "python"),
                objectMapper);
        CodeAnalystAgent realCodeAnalyst = new CodeAnalystAgent(registry, realAnalysisService);

        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, new ReadOnlySqlGuard(), properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler,
                planState, roleRecorder, llmLimiter, toolAssistant, toolPolicy, toolOutputSanitizer,
                selectionService, pythonAssistant, realCodeAnalyst, reportService, reportComposer,
                realAnalysisService);
        service = new AgentRunService(
                repository, conversationService, connectionService, sqlAssistant, objectMapper,
                knowledgeService, configResolver, graphStateService, approvalSessionStore,
                engine, contextAssembler, failureHandler, planState, roleRecorder, llmLimiter,
                new ToolInvocationRequestGuard(),
                planReviewGate, planReviewSessionStore, planner, graphRunner);

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
                           String role, String sql, int sqlVersion, ApprovalStatus approval) {
        return new AgentStep(id, RUN_ID, sequence, objective, dependsOn, status, sql, sqlVersion,
                role, approval, 0, null, null, null, null, null, NOW, NOW, null, null);
    }

    private static QueryResultResponse queryResult() {
        return new QueryResultResponse(
                List.of("region", "total"),
                List.of(Map.of("region", "华东", "total", 1200), Map.of("region", "华南", "total", 900)),
                2,
                false);
    }

    private String startSqlThenCodePlan() {
        when(managerRouter.route(any(ManagerRouter.RoutingContext.class)))
                .thenReturn(new ManagerRouter.RouteDecision(AgentRoute.COMPLEX, "需要多步分析"));
        when(planner.plan(eq(QUESTION), eq(SCHEMA), anyString()))
                .thenReturn(new AgentPlan(true, "", List.of(
                        new AgentPlan.Step(OBJECTIVE, "DATA_ANALYST", null),
                        new AgentPlan.Step(CODE_OBJECTIVE, "CODE_ANALYST", 1))));
        when(repository.insertStep(RUN_ID, 1, OBJECTIVE, null, "DATA_ANALYST")).thenReturn(STEP_ID);
        when(repository.insertStep(RUN_ID, 2, CODE_OBJECTIVE, 1, "CODE_ANALYST")).thenReturn(CODE_STEP_ID);
        when(sqlAssistant.generateCandidateSql(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString()))
                .thenReturn(SQL);
        when(sqlAssistant.scoreCandidates(anyString(), anyString(), any()))
                .thenReturn("[{\"candidate\":1,\"score\":9}]");
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.PENDING, "DATA_ANALYST", null, 0,
                        ApprovalStatus.PENDING),
                step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, "CODE_ANALYST", null, 0,
                        ApprovalStatus.PENDING)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.startRun(prepared(), output);
        return output.toString(StandardCharsets.UTF_8);
    }


    @Test
    @DisplayName("真实容器：模型代码抛错时 Code 步骤真实失败，不落成功工件")
    void realCodeStep_failureFailsWithoutFabrication() {
        startSqlThenCodePlan();
        when(executionService.execute(eq(USER_ID), any(ExecuteSqlRequest.class), eq(RUN_ID), eq(STEP_ID)))
                .thenReturn(queryResult());
        when(repository.findSteps(RUN_ID)).thenReturn(List.of(
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.SUCCEEDED, "DATA_ANALYST", SQL, 1,
                        ApprovalStatus.APPROVED),
                step(CODE_STEP_ID, 2, CODE_OBJECTIVE, 1, StepStatus.PENDING, "CODE_ANALYST", null, 0,
                        ApprovalStatus.PENDING)));
        when(repository.claimNonSqlStep(RUN_ID, CODE_STEP_ID)).thenReturn(1);
        when(repository.failNonSqlStep(eq(RUN_ID), eq(CODE_STEP_ID), anyString(), anyString())).thenReturn(1);
        // 生成会抛 RuntimeError 的真实代码：真实容器必须失败关闭。
        when(pythonAssistant.generateCode(eq(RUN_ID), eq(CODE_OBJECTIVE), anyString(), anyString(),
                anyString(), anyString())).thenReturn("raise RuntimeError('real-container-boom')\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.executeApproval(new PreparedApproval(
                USER_ID, run(RunStatus.WAITING_APPROVAL, AgentRoute.COMPLEX.name()),
                step(STEP_ID, 1, OBJECTIVE, null, StepStatus.WAITING_APPROVAL, "DATA_ANALYST", SQL, 1,
                        ApprovalStatus.PENDING),
                false, DATABASE, null), out);
        String output = out.toString(StandardCharsets.UTF_8);

        // 真实失败：标记步骤失败与运行失败，绝不冒充成功。
        assertThat(output).contains("event: analysis.failed", "event: step.failed", "event: run.failed");
        assertThat(output).doesNotContain("event: analysis.succeeded");
        assertThat(output).doesNotContain("event: run.completed");
        verify(repository, never()).completeCodeStep(anyLong(), anyLong(), any(), anyString());
        // 真实分析层也只落一次失败（退出码缺陷修复在真实路径下成立）。
        verify(analysisRepository, never()).completeRun(anyLong(), anyString(), anyString(), anyInt(), anyLong());
    }
}
