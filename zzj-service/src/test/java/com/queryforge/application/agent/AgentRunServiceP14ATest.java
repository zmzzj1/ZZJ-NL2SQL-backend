package com.queryforge.application.agent;

import com.queryforge.application.agent.runtime.AgentGraphRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.queryforge.application.agent.roles.RoleAssigner;
import com.queryforge.application.agent.roles.ToolAssistantAgent;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.application.memory.MemoryService;
import com.queryforge.application.skill.SkillGuidanceSelector;
import com.queryforge.application.skill.SkillService;
import com.queryforge.application.tool.AgentRunToolPolicy;
import com.queryforge.application.tool.ToolOutputSanitizer;
import com.queryforge.domain.agent.AgentProperties;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.domain.agent.CreateAgentRunRequest;
import com.queryforge.domain.agent.CreateAgentRunRequest.ToolInvocationRequest;
import com.queryforge.domain.agent.RunStatus;
import com.queryforge.domain.agent.StepStatus;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.conversation.MessageRole;
import com.queryforge.domain.conversation.MessageStatus;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.domain.tool.ToolInvocationResult;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.redis.AgentPlanStore;
import com.queryforge.infrastructure.redis.ApprovalSessionStore;
import com.queryforge.infrastructure.redis.PlanReviewSessionStore;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.HttpStatus;
import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * P14-A：Manager 闲聊/澄清真实状态与显式工具快捷路径（T2/T3/T4）。
 * 只覆盖本轮新增逻辑，不重复既有 SQL 审批链测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P14-A 闲聊、澄清与显式工具快捷路径")
class AgentRunServiceP14ATest {

    @Mock
    private AgentGraphRunner graphRunner;

    private static final long USER_ID = 1L;
    private static final long RUN_ID = 5L;
    private static final long CONVERSATION_ID = 7L;
    private static final long CONNECTION_ID = 10L;
    private static final long STEP_ID = 11L;
    private static final String QUESTION = "你好";
    private static final String SCHEMA = "数据库：sql_demo_a\nshop_orders(id BIGINT, amount DECIMAL)";
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
    private com.queryforge.application.agent.sql.SqlCandidateSelectionService selectionService;

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
        engine = new AgentStepEngine(
                repository, conversationService, connectionService, planner, sqlAssistant,
                executionService, guard, properties, managerRouter, graphStateService,
                roleAssigner, approvalSessionStore, contextAssembler, failureHandler,
                planState, roleRecorder, llmLimiter, toolAssistant, toolPolicy, toolOutputSanitizer,
                selectionService, pythonAssistant, codeAnalyst, reportService, reportComposer,
                analysisService);
        service = new AgentRunService(
                repository, conversationService, connectionService, sqlAssistant, objectMapper,
                knowledgeService, configResolver, graphStateService, approvalSessionStore,
                engine, contextAssembler, failureHandler, planState, roleRecorder, llmLimiter,
                new ToolInvocationRequestGuard(),
                planReviewGate, planReviewSessionStore, planner, graphRunner);
        lenient().when(repository.tryIncrementLlmCalls(anyLong(), anyInt())).thenReturn(1);
    }

    private void stubCommon() {
        when(connectionService.schemaContext(USER_ID, CONNECTION_ID)).thenReturn(SCHEMA);
        when(conversationService.recentCompletedMessages(CONVERSATION_ID, 6)).thenReturn(List.of());
        when(conversationService.memorySummary(CONVERSATION_ID)).thenReturn(null);
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
    }

    private PreparedRun prepared() {
        return new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "sql_demo_a", QUESTION);
    }

    private PreparedRun preparedWithTool(String toolName, Map<String, Object> args) {
        return new PreparedRun(RUN_ID, USER_ID, CONVERSATION_ID, CONNECTION_ID, "sql_demo_a", QUESTION,
                ResolvedAgentConfig.defaults(), null, new ToolInvocationRequest(toolName, args), Boolean.TRUE);
    }

    private String start(PreparedRun prepared) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.startRun(prepared, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------- T2 闲聊


    // ------------------------------------------------------------------- T3 澄清


    // --------------------------------------------------------------- T4 显式工具


    @Test
    @DisplayName("T4：显式工具请求在服务端做边界校验（非法工具名直接拒绝）")
    void prepareStart_shouldRejectInvalidToolInvocation() {
        when(conversationService.requireMatchingConversation(USER_ID, CONVERSATION_ID, CONNECTION_ID))
                .thenReturn(CONVERSATION_ID);
        when(connectionService.summary(USER_ID, CONNECTION_ID))
                .thenReturn(new ConnectionService.ConnectionSummary(CONNECTION_ID, "A 商店库", "sql_demo_a"));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.prepareStart(
                USER_ID, new CreateAgentRunRequest(
                        CONVERSATION_ID, CONNECTION_ID, "查看表结构", null, null, null, null,
                        new ToolInvocationRequest("describe schema; drop", Map.of()), null))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                });
        verify(repository, never()).insertRun(any(), any(), any(), any(), anyString(), any(), any(), any(),
                anyString(), anyString());
    }

}
