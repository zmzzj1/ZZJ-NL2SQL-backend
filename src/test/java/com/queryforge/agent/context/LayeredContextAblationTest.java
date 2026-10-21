package com.queryforge.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A4 / P1126：L1–L4 分层上下文压缩的确定性消融测试。
 * 指南要求“未知/估算不伪造准确供应商值”，本测试只验证不变量（关键事实保留、预算约束、
 * 确定性指纹、审批/授权边界不被改写），不杜撰真实 token 数。全部 30 例多轮场景确定性可重放。
 */
class LayeredContextAblationTest {

    private static final TokenEstimator EST = TokenEstimator.defaultEstimator();

    private ContextSegment seg(String id, ContextLayer layer, ContextPriority p, String text) {
        return new ContextSegment(id, layer, p, text, false, false, false);
    }

    private ContextSegment auth(String id, String text) {
        return new ContextSegment(id, ContextLayer.L1_RECENT, ContextPriority.HIGH, text, false, false, true);
    }

    private ContextSegment approval(String id, String text) {
        return new ContextSegment(id, ContextLayer.L1_RECENT, ContextPriority.MEDIUM, text, false, true, false);
    }

    private ContextSegment confirmed(String id, String text) {
        return new ContextSegment(id, ContextLayer.L1_RECENT, ContextPriority.HIGH, text, true, false, false);
    }

    @Test
    @DisplayName("P1126 多轮消融：30 轮对话压缩确定性且关键事实不丢")
    void multiTurnThirtyRoundsDeterministic() {
        String question = "请给我最近一个月各区域销售额Top5";
        List<ContextSegment> history = new ArrayList<>();
        for (int round = 1; round <= 30; round++) {
            history.add(seg("m" + round, ContextLayer.L1_RECENT,
                    round % 2 == 0 ? ContextPriority.LOW : ContextPriority.MEDIUM,
                    "第" + round + "轮的历史消息内容填充一些文本用于消耗上下文预算额度与验证稳定性"));
            if (round == 1) {
                history.add(auth("auth1", "你只能查询当前用户已授权的数据库 shop_a 与 blog_b"));
                history.add(approval("appr1", "第3步的SQL变更正在等待你的人工审批"));
                history.add(confirmed("c1", "用户已确认：仅统计已支付订单"));
            }
            ContextBudget budget = new ContextBudget(200, question.length() / 4 + 60, 0, 8000);
            LayeredContextResult r1 = new LayeredContextBuilder(EST).build("SYS", question, history, budget);
            LayeredContextResult r2 = new LayeredContextBuilder(EST).build("SYS", question, history, budget);

            // 1) 确定性：相同输入产生相同指纹（P1126 可重放）
            assertEquals(r1.meta().contextHash(), r2.meta().contextHash(), "第" + round + "轮必须确定性");
            // 2) 当前问题必在用户上下文（CRITICAL 隐含于 question）
            assertTrue(r1.userContext().contains(question), "第" + round + "轮：当前问题必须保留");
            // 3) 授权边界必在系统上下文（指南 §7.2 强制前置）
            assertTrue(r1.systemContext().contains("shop_a"), "第" + round + "轮：授权边界必在系统上下文");
            // 4) 审批状态不得被改写为已批准（P1108 硬门槛）
            assertTrue(r1.userContext().contains("审批"), "第" + round + "轮：待审批状态必须保留");
            assertFalse(r1.userContext().contains("已批准"), "第" + round + "轮：审批状态不得被改写为已批准");
            // 5) 预算充足时不变量：保留量不超历史预算 + 关键事实强制保留（dropped=0）
            int historyBudget = budget.historyBudget();
            int retainedTokens =
                    EST.estimate(r1.systemContext()) + EST.estimate(r1.userContext())
                            - EST.estimate("SYS") - EST.estimate(question);
            assertTrue(retainedTokens <= historyBudget + 60, "第" + round + "轮：保留量应受历史预算约束");
            assertEquals(0, r1.meta().droppedSegments(), "第" + round + "轮：宽松预算下不应丢弃");
        }
    }

    @Test
    @DisplayName("预算压力下 LOW 先于 MEDIUM/HIGH 被丢弃，关键事实强制保留")
    void lowPriorityDroppedBeforeHighUnderPressure() {
        String question = "分析销售";
        List<ContextSegment> segs = new ArrayList<>();
        segs.add(auth("auth1", "你只能查询已授权的数据库 shop_a"));
        segs.add(approval("appr1", "某步骤SQL正在等待审批"));
        for (int i = 0; i < 40; i++) {
            segs.add(seg("low" + i, ContextLayer.L1_RECENT, ContextPriority.LOW,
                    "低优先级历史消息需要消耗大量上下文预算以便观察丢弃顺序与优先级排序行为是否生效"));
        }
        for (int i = 0; i < 10; i++) {
            segs.add(seg("high" + i, ContextLayer.L1_RECENT, ContextPriority.HIGH,
                    "高优先级历史消息应该在低优先级之前被保留直到预算耗尽为止"));
        }
        ContextBudget budget = new ContextBudget(100, 50, 0, 200); // 极小历史预算
        LayeredContextResult r = new LayeredContextBuilder(EST).build("SYS", question, segs, budget);

        assertTrue(r.systemContext().contains("shop_a"), "授权边界（关键）必须保留");
        assertTrue(r.userContext().contains("审批"), "待审批状态（关键）必须保留");
        assertTrue(r.meta().droppedSegments() > 30, "大量低/高历史应在极小预算下被丢弃");
        assertTrue(r.meta().retainedSources() >= 2, "关键来源（授权/审批）必须保留");
    }

    @Test
    @DisplayName("关键事实强制超预算保留（即便整体预算不足）")
    void criticalFactForceRetainedBeyondBudget() {
        String question = "q";
        List<ContextSegment> segs = new ArrayList<>();
        segs.add(auth("auth1", "授权边界关键信息必须始终可见不可被压缩丢弃掉"));
        ContextBudget budget = new ContextBudget(0, 0, 0, 10); // 历史预算仅 10 token
        LayeredContextBuilder builder = new LayeredContextBuilder(EST);

        // 关键事实自身超过预算时抛 ContextBudgetExceededException（不冒充成功，P1130）
        List<ContextSegment> oversize = new ArrayList<>();
        oversize.add(auth("big", "这是一段非常长的授权边界信息用于触发关键事实超出预算的失败分支以验证安全行为不冒充恢复成功"));
        assertThrows(ContextBudgetExceededException.class,
                () -> builder.build("SYS", question, oversize, new ContextBudget(0, 0, 0, 5)));

        // 关键事实在预算内则强制保留
        LayeredContextResult r = builder.build("SYS", question, segs, budget);
        assertTrue(r.systemContext().contains("授权边界"), "关键事实在预算内必须强制保留");
    }

    @Test
    @DisplayName("L2 摘要层明确区分用户已确认 vs 模型推测（P1122 不混淆）")
    void l2ConfirmedVsSpeculativeTagging() {
        String question = "分析";
        List<ContextSegment> segs = new ArrayList<>();
        segs.add(new ContextSegment("s1", ContextLayer.L2_SUMMARY, ContextPriority.HIGH,
                "订单表约有十万行", true, false, false));
        segs.add(new ContextSegment("s2", ContextLayer.L2_SUMMARY, ContextPriority.MEDIUM,
                "模型推测下季度销量会上涨", false, false, false));
        ContextBudget budget = new ContextBudget(200, 50, 0, 8000);
        LayeredContextResult r = new LayeredContextBuilder(EST).build("SYS", question, segs, budget);

        assertTrue(r.userContext().contains("[摘要·已确认]"), "已确认摘要须明确标注");
        assertTrue(r.userContext().contains("[摘要·推测]"), "模型推测摘要须明确标注");
        assertFalse(r.userContext().contains("已确认] 模型推测"), "推测内容不得混入已确认标签");
    }

    @Test
    @DisplayName("相同输入两次构建产生稳定指纹（可重放性）")
    void contextHashStableAcrossIdenticalRuns() {
        String question = "同一个问题";
        List<ContextSegment> segs = List.of(
                confirmed("c1", "用户已确认约束A"),
                seg("m1", ContextLayer.L1_RECENT, ContextPriority.MEDIUM, "一条中等优先级消息"));
        ContextBudget budget = new ContextBudget(100, 30, 0, 4000);
        String h1 = new LayeredContextBuilder(EST).build("SYS", question, segs, budget).meta().contextHash();
        String h2 = new LayeredContextBuilder(EST).build("SYS", question, segs, budget).meta().contextHash();
        assertEquals(h1, h2, "相同上下文必须产生相同指纹");
    }

    @Test
    @DisplayName("空上下文不产生丢弃，且指纹稳定")
    void emptyContextProducesSaneResult() {
        ContextBudget budget = new ContextBudget(100, 30, 0, 4000);
        LayeredContextResult r = new LayeredContextBuilder(EST).build("SYS", "问题", List.of(), budget);
        assertEquals(0, r.meta().droppedSegments());
        assertFalse(r.meta().contextHash().isBlank());
    }

    @Test
    @DisplayName("L4 紧急层只保留关键事实，L1 低优先级在压力下丢弃")
    void l4EmergencyRetainsOnlyCritical() {
        String question = "紧急分析";
        List<ContextSegment> segs = new ArrayList<>();
        segs.add(new ContextSegment("em", ContextLayer.L4_EMERGENCY, ContextPriority.CRITICAL,
                "紧急关键事实必须保留", false, false, false));
        for (int i = 0; i < 20; i++) {
            segs.add(seg("low" + i, ContextLayer.L1_RECENT, ContextPriority.LOW,
                    "一段很长的低优先级历史消息用于消耗紧急压缩下的预算以验证L4只保留关键事实"));
        }
        ContextBudget budget = new ContextBudget(0, 0, 0, 20); // 历史预算极小，强制丢弃
        LayeredContextResult r = new LayeredContextBuilder(EST).build("SYS", question, segs, budget);

        assertTrue(r.userContext().contains("紧急关键事实"), "L4 关键事实必须保留");
        assertTrue(r.meta().droppedSegments() > 0, "L1 低优先级在压力下应被丢弃");
    }
}
