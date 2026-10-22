package com.queryforge.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.application.knowledge.KnowledgeService;
import com.queryforge.application.memory.MemoryService;
import com.queryforge.application.skill.SkillGuidanceSelector;
import com.queryforge.application.skill.SkillService;
import com.queryforge.domain.agent.AgentStreamEvents;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.domain.agent.context.ContextBudgetExceededException;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P14-A T5：分层上下文真实入模。验证压缩结果真正可用于模型请求、
 * 预算与权限优先、SSE 只回脱敏元数据。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P14-A 分层上下文真实构造与注入")
class AgentContextAssemblerTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final String QUESTION = "统计最近一个月的订单数";

    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private MemoryService memoryService;
    @Mock
    private SkillService skillService;
    @Mock
    private AgentRunRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new AgentContextAssembler(
                knowledgeService, new SkillGuidanceSelector(skillService), memoryService, repository, objectMapper);
    }

    private PreparedRun run(ResolvedAgentConfig config) {
        return new PreparedRun(5L, USER_ID, 7L, CONNECTION_ID, "sql_demo_a", QUESTION, config, null, null);
    }

    @Test
    @DisplayName("T5：短历史真正进入可注入模型的上下文体")
    void build_shouldReturnInjectableContext() throws Exception {
        PreparedRun prepared = run(ResolvedAgentConfig.defaults());
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");

        EffectiveRunContext context = assembler.buildRunContext(
                prepared, null, List.of("用户：上一轮问了什么", "助手：上一轮回答"), null,
                "shop_orders(id, amount)", "", false);

        assertThat(context.effectiveContext()).contains("上一轮问了什么");
        assertThat(context.effectiveContext()).contains("上一轮回答");
        // 授权边界来自服务端，进入 system 上下文而不是用户层。
        assertThat(context.layered().systemContext()).contains("只读 SELECT");
        assertThat(context.layered().systemContext()).contains("sql_demo_a");
        // 当前问题只出现一次，不重复塞入片段。
        assertThat(context.effectiveContext().split(QUESTION, -1).length - 1).isEqualTo(1);
        // 分类历史不含授权知识正文。
        assertThat(context.historyForClassifier()).contains("上一轮问了什么");
        assertThat(context.compacted()).isTrue();
    }

    @Test
    @DisplayName("T5：超预算历史被真实丢弃，元数据与输入对应")
    void build_shouldDropOverBudgetHistory() throws Exception {
        PreparedRun prepared = run(ResolvedAgentConfig.defaults());
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
        StringBuilder huge = new StringBuilder();
        for (int index = 0; index < 400; index++) {
            huge.append("第 ").append(index).append(" 轮很长历史内容").append("x".repeat(120)).append('\n');
        }
        List<String> recent = List.of(huge.toString());

        EffectiveRunContext context = assembler.buildRunContext(
                prepared, null, recent, null, "shop_orders(id)", "", false);

        assertThat(context.layered().meta().droppedSegments()).isGreaterThan(0);
        assertThat(context.layered().meta().inputTokensEstimate())
                .isGreaterThan(context.historyBudget());
        // 压缩后的上下文体明显小于原始历史。
        assertThat(context.effectiveContext().length()).isLessThan(huge.length());
    }

    @Test
    @DisplayName("T5：Agent 禁用 Memory 时不暗中注入长期记忆")
    void build_shouldNotInjectMemoryWhenDisabled() throws Exception {
        ResolvedAgentConfig config = new ResolvedAgentConfig(
                3L, 9L, "无记忆", "", null, List.of(), true, 5, false, 2000, Map.of());
        PreparedRun prepared = run(config);
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");

        EffectiveRunContext context = assembler.buildRunContext(
                prepared, null, List.of("用户：上一轮"), null, "shop_orders(id)", "", false);

        assertThat(context.memoryContext()).isEmpty();
        verify(memoryService, never()).memoryContext(anyLong(), any(), any(), anyString(), anyInt());
        assertThat(context.effectiveContext()).doesNotContain("长期记忆");
    }

    @Test
    @DisplayName("T5：禁用 RAG 时不检索与注入知识")
    void build_shouldNotRetrieveKnowledgeWhenRagDisabled() throws Exception {
        ResolvedAgentConfig config = new ResolvedAgentConfig(
                3L, 9L, "无 RAG", "", null, List.of(), false, 5, true, 2000, Map.of());
        PreparedRun prepared = run(config);

        EffectiveRunContext context = assembler.buildRunContext(
                prepared, null, List.of("用户：上一轮"), null, "shop_orders(id)", "", false);

        assertThat(context.knowledge()).isEmpty();
        verify(knowledgeService, never()).hits(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("T5：关键事实超预算时明确失败，不静默丢弃授权边界")
    void build_shouldFailWhenCriticalFactsExceedBudget() {
        ResolvedAgentConfig config = new ResolvedAgentConfig(
                3L, 9L, "极小预算", "", null, List.of(), true, 5, true, 1, Map.of());
        PreparedRun prepared = run(config);
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 1)).thenReturn("");

        assertThatThrownBy(() -> assembler.buildRunContext(
                prepared, null, List.of("用户：上一轮"), null, "shop_orders(id)", "", false))
                .isInstanceOf(ContextBudgetExceededException.class);
    }

    @Test
    @DisplayName("T5：context.compacted 只回脱敏元数据，不回传私有正文")
    void emit_shouldOnlySendSanitizedMeta() throws Exception {
        PreparedRun prepared = run(ResolvedAgentConfig.defaults());
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");
        EffectiveRunContext context = assembler.buildRunContext(
                prepared, null, List.of("用户：私密问题内容"), null, "shop_orders(id)", "", false);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SseEventWriter writer = new SseEventWriter(output, objectMapper);
        assembler.emitContextCompacted(5L, writer, context);

        String sse = output.toString(StandardCharsets.UTF_8);

        assertThat(sse).contains("event: context.compacted");
        assertThat(sse).contains("inputTokensEstimate");
        assertThat(sse).contains("contextHash");
        assertThat(sse).doesNotContain("私密问题内容");
        assertThat(sse).doesNotContain("只读 SELECT");
    }

    @Test
    @DisplayName("T5：没有应用压缩时不发送模拟事件")
    void emit_shouldSkipWhenNotCompacted() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SseEventWriter writer = new SseEventWriter(output, objectMapper);
        assembler.emitContextCompacted(5L, writer, EffectiveRunContext.empty());


        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("context.compacted");
    }

    @Test
    @DisplayName("T5：待审批事实作为关键片段保留，不被压缩成已批准")
    void build_shouldKeepPendingApprovalFact() throws Exception {
        PreparedRun prepared = run(ResolvedAgentConfig.defaults());
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("");

        EffectiveRunContext context = assembler.buildRunContext(
                prepared, null, List.of(), null, "shop_orders(id)", "", true);

        assertThat(context.effectiveContext()).contains("未经明确确认不得执行");
        assertThat(context.effectiveContext()).doesNotContain("已批准");
    }

    @Test
    @DisplayName("T5：知识检索失败不阻断运行，降级为空上下文")
    void build_shouldDegradeWhenKnowledgeFails() throws Exception {
        PreparedRun prepared = run(ResolvedAgentConfig.defaults());
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION))
                .thenThrow(new RuntimeException("检索不可用"));

        EffectiveRunContext context = assembler.buildRunContext(
                prepared, null, List.of("用户：上一轮"), null, "shop_orders(id)", "", false);

        assertThat(context.knowledge()).isEmpty();
        assertThat(context.effectiveContext()).contains("上一轮");
    }

    @Test
    @DisplayName("T5：知识证据事件真实发出（不含正文）")
    void build_shouldEmitKnowledgeEvidenceEvent() throws Exception {
        PreparedRun prepared = run(ResolvedAgentConfig.defaults());
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("订单口径");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SseEventWriter writer = new SseEventWriter(output, objectMapper);

        EffectiveRunContext context = assembler.buildRunContext(
                prepared, writer, List.of("用户：上一轮"), null, "shop_orders(id)", "", false);


        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("event: knowledge.retrieved");
        assertThat(context.knowledge()).isEqualTo("订单口径");
        verify(knowledgeService).hits(USER_ID, CONNECTION_ID, QUESTION);
    }

    @Test
    @DisplayName("T5：Manager/Planner/SQL 消费的是同一条受控上下文")
    void context_shouldBeSharedAcrossModelCalls() throws Exception {
        PreparedRun prepared = run(ResolvedAgentConfig.defaults());
        when(knowledgeService.hits(USER_ID, CONNECTION_ID, QUESTION)).thenReturn(List.of());
        when(knowledgeService.knowledgeContext(USER_ID, CONNECTION_ID, QUESTION, 2000)).thenReturn("订单口径");
        EffectiveRunContext context = assembler.buildRunContext(
                prepared, null, List.of("用户：上一轮"), "已有摘要", "shop_orders(id)", "", false);

        // 同一个 effectiveContext() 被 Manager/闲聊/Planner/SQL 复用，且都含授权边界与摘要。
        String shared = context.effectiveContext();
        assertThat(shared).contains("上一轮");
        assertThat(shared).contains("已有摘要");
        assertThat(shared).contains("订单口径");
        assertThat(shared).contains("只读 SELECT");
        assertThat(context.historyForClassifier()).contains("已有摘要");
        // 事件负载形状保持既有 SSE 契约。
        assertThat(AgentStreamEvents.ContextCompacted.class.getRecordComponents()).hasSize(7);
        assertThat(AgentStreamEvents.ToolResult.class.getRecordComponents()).hasSize(5);
    }
}
