package com.queryforge.domain.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LayeredContextBuilderTest {

    private static final TokenEstimator EST = TokenEstimator.defaultEstimator();

    private ContextSegment seg(String id, ContextLayer layer, ContextPriority p, String text,
                               boolean confirmed, boolean approval, boolean auth) {
        return new ContextSegment(id, layer, p, text, confirmed, approval, auth);
    }

    @Test
    void tokenEstimator_usesCharApproximation_notExact() {
        assertThat(EST.estimate("1234")).isEqualTo(1);
        assertThat(EST.estimate("12345")).isEqualTo(2);
        assertThat(EST.estimate("")).isZero();
        assertThat(EST.estimate((String) null)).isZero();
    }

    @Test
    void l1_recentMessagesRetainedWithinBudget() {
        List<ContextSegment> segments = List.of(
                seg("m1", ContextLayer.L1_RECENT, ContextPriority.HIGH, "用户要求统计销售额", true, false, false),
                seg("m2", ContextLayer.L1_RECENT, ContextPriority.MEDIUM, "时间范围：近半年", true, false, false),
                seg("m3", ContextLayer.L1_RECENT, ContextPriority.LOW, "无关闲聊内容", false, false, false));
        ContextBudget budget = new ContextBudget(100, 20, 50, 2000);
        LayeredContextResult result = new LayeredContextBuilder(EST).build("SYS", "Q", segments, budget);

        assertThat(result.userContext()).contains("用户要求统计销售额");
        assertThat(result.userContext()).contains("时间范围：近半年");
        assertThat(result.userContext()).contains("无关闲聊内容");
        assertThat(result.meta().droppedSegments()).isZero();
        assertThat(result.meta().layersUsed()).contains(ContextLayer.L1_RECENT);
        assertThat(result.meta().contextHash()).hasSize(16);
    }

    @Test
    void l2_summary_marksConfirmedVsSpeculative_notAutoApproved() {
        List<ContextSegment> segments = List.of(
                seg("s1", ContextLayer.L2_SUMMARY, ContextPriority.MEDIUM, "订单表在目标库存在", true, false, false),
                seg("s2", ContextLayer.L2_SUMMARY, ContextPriority.LOW, "推测总销售额约上涨", false, false, false),
                seg("s3", ContextLayer.L1_RECENT, ContextPriority.HIGH, "当前问题：趋势分析", true, false, false));
        ContextBudget budget = new ContextBudget(100, 20, 50, 2000);
        LayeredContextResult result = new LayeredContextBuilder(EST).build("SYS", "Q", segments, budget);

        assertThat(result.userContext()).contains("[摘要·已确认]");
        assertThat(result.userContext()).contains("[摘要·推测]");
        // 待审批状态不得被记为已批准
        assertThat(result.userContext()).doesNotContain("已批准");
    }

    @Test
    void l3_retrieval_includedWhenBudgetAllows() {
        List<ContextSegment> segments = List.of(
                seg("k1", ContextLayer.L3_RETRIEVAL, ContextPriority.HIGH, "知识库：销售额=amount字段求和", false, false, false),
                seg("m1", ContextLayer.L1_RECENT, ContextPriority.HIGH, "问趋势", true, false, false));
        ContextBudget budget = new ContextBudget(100, 20, 50, 2000);
        LayeredContextResult result = new LayeredContextBuilder(EST).build("SYS", "Q", segments, budget);

        assertThat(result.userContext()).contains("[检索]");
        assertThat(result.userContext()).contains("销售额=amount字段求和");
        assertThat(result.meta().retainedSources()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void l4_emergency_keepsApprovalAndAuthorization_dropsLowPriority() {
        // 极小预算：只有关键事实（待审批/授权/已确认 L1）能留下，LOW L1 被丢弃
        List<ContextSegment> segments = List.of(
                seg("a1", ContextLayer.L1_RECENT, ContextPriority.LOW, "可丢弃的细枝末节", false, false, false),
                seg("a2", ContextLayer.L1_RECENT, ContextPriority.CRITICAL, "用户已确认的口径约束", true, false, false),
                seg("appr", ContextLayer.L1_RECENT, ContextPriority.HIGH, "待审批 SQL：SELECT 1", false, true, false),
                seg("auth", ContextLayer.L1_RECENT, ContextPriority.HIGH, "仅可读 shop_orders", false, false, true));
        // historyBudget = 12 token：关键事实（CRITICAL 已确认 + 待审批 + 授权）共约 11 token 可装入，
        // LOW 的“可丢弃的细枝末节”(约 2 token) 超出剩余预算被丢弃。
        ContextBudget budget = new ContextBudget(0, 0, 0, 12);
        LayeredContextResult result = new LayeredContextBuilder(EST).build("SYS", "Q", segments, budget);

        assertThat(result.userContext()).contains("待审批 SQL：SELECT 1");
        // 授权边界按安全设计固定在 systemContext（始终可见，不参与历史预算裁剪）
        assertThat(result.systemContext()).contains("[授权边界] 仅可读 shop_orders");
        assertThat(result.userContext()).contains("用户已确认的口径约束");
        assertThat(result.userContext()).doesNotContain("可丢弃的细枝末节");
    }

    @Test
    void budgetExceeded_whenCriticalFactsAloneSurpassHardBudget() {
        // 单条 CRITICAL 授权事实 token 数超过可用历史预算
        List<ContextSegment> segments = List.of(
                seg("auth", ContextLayer.L1_RECENT, ContextPriority.CRITICAL,
                        "x".repeat(4000), false, false, true));
        ContextBudget budget = new ContextBudget(0, 0, 0, 100); // historyBudget=100，远小于 ~1000 token
        assertThatThrownBy(() -> new LayeredContextBuilder(EST).build("SYS", "Q", segments, budget))
                .isInstanceOf(ContextBudgetExceededException.class)
                .satisfies(ex -> {
                    ContextBudgetExceededException e = (ContextBudgetExceededException) ex;
                    assertThat(e.getRequiredTokens()).isGreaterThan(e.getAvailableTokens());
                });
    }

    @Test
    void metadata_recordsDroppedSegmentsAndLayers() {
        List<ContextSegment> segments = List.of(
                seg("m1", ContextLayer.L1_RECENT, ContextPriority.HIGH, "近消息", true, false, false),
                seg("m2", ContextLayer.L1_RECENT, ContextPriority.LOW, "冗长历史片段内容", false, false, false),
                seg("s1", ContextLayer.L2_SUMMARY, ContextPriority.MEDIUM, "旧摘要", true, false, false));
        // 紧预算（3 token）：已确认片段（m1/s1 共 2 token）强制保留，LOW 的 m2(3 token) 超出被丢弃
        ContextBudget budget = new ContextBudget(0, 0, 0, 3);
        LayeredContextResult result = new LayeredContextBuilder(EST).build("SYS", "Q", segments, budget);

        assertThat(result.meta().droppedSegments()).isGreaterThan(0);
        assertThat(result.meta().retainedSources()).isGreaterThan(0);
        assertThat(result.meta().layersUsed()).isNotEmpty();
        assertThat(result.meta().inputTokensEstimate()).isGreaterThan(0);
        assertThat(result.meta().summaryVersion()).isNotBlank();
    }
}
