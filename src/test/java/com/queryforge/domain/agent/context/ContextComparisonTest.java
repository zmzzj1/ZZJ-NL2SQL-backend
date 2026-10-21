package com.queryforge.domain.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 三组上下文方案对比（指南 §7.3 / P1131 的确定性部分）：
 * <ul>
 *   <li>A — 不压缩/直接拼接（无预算）</li>
 *   <li>B — 简单截断（按字符数截断）</li>
 *   <li>C — 当前 L1–L4 分层上下文</li>
 * </ul>
 *
 * <p>确定性可复现地记录「实际送入模型的上下文 Token 估算」「是否保留关键 Schema/业务知识/授权边界」。
 * SQL 生成成功率、SQL 语义正确率依赖真实模型，本环境不可复现 → 记为 BLOCKED（见 context-evaluation 文档），
 * 本测试只证明可观测指标的确定性差异，不伪造模型效果。
 */
class ContextComparisonTest {

    private static final TokenEstimator EST = TokenEstimator.defaultEstimator();

    private static final String QUESTION = "统计各区域已支付订单的销量";
    private static final String SCHEMA = "shop_orders(id, region, amount, status, created_at)";
    /** 关键业务口径与授权边界放在历史“尾部”，以暴露简单截断的丢尾缺陷。 */
    private static final String KEY_KNOWLEDGE = "关键口径：销量 = status='paid' 的订单金额之和";
    private static final String AUTH_BOUNDARY = "授权边界：你只能查询当前用户已授权的数据库 shop_a";

    private record SchemeResult(String name, int contextTokens, boolean retainsKeyKnowledge, boolean retainsAuth) {
    }

    @Test
    @DisplayName("三组方案在相同任务下的 Token/关键知识/授权边界保留对比")
    void threeSchemes_retentionAndTokens() {
        // 60 条长历史，把关键口径与授权边界放在末尾
        List<String> history = longHistory();
        history.add(KEY_KNOWLEDGE);
        history.add(AUTH_BOUNDARY);

        SchemeResult a = schemeA(QUESTION, SCHEMA, history);
        SchemeResult b = schemeB(QUESTION, SCHEMA, history, 1500);
        SchemeResult c = schemeC(QUESTION, history);

        // A：无预算，全保留，Token 最大
        assertThat(a.retainsKeyKnowledge()).isTrue();
        assertThat(a.retainsAuth()).isTrue();

        // B：截断丢尾 → 关键口径与授权边界在尾部被丢弃，Token 更小
        assertThat(b.retainsKeyKnowledge()).isFalse();
        assertThat(b.retainsAuth()).isFalse();
        assertThat(b.contextTokens()).isLessThan(a.contextTokens());

        // C：L1–L4 在预算压力下仍保留关键事实（关键口径 CRITICAL、授权边界强制前置），Token 更小
        assertThat(c.retainsKeyKnowledge()).isTrue();
        assertThat(c.retainsAuth()).isTrue();
        assertThat(c.contextTokens()).isLessThan(a.contextTokens());
        assertThat(c.contextTokens()).isLessThan(b.contextTokens());
    }

    @Test
    @DisplayName("C 方案在极小预算下仍保留授权边界（A/B 无此语义区分）")
    void layered_retainsAuthUnderPressure_whereTruncationLoses() {
        List<String> history = longHistory();
        history.add(AUTH_BOUNDARY);

        SchemeResult c = schemeC(QUESTION, history);
        assertThat(c.retainsAuth()).isTrue();

        // 简单截断无法区分「关键授权边界」与「普通历史」，尾部授权边界被丢弃
        SchemeResult b = schemeB(QUESTION, SCHEMA, history, 1500);
        assertThat(b.retainsAuth()).isFalse();
    }

    // ---- 三组方案实现 ----

    private static SchemeResult schemeA(String question, String schema, List<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append(question).append('\n').append(schema).append('\n');
        history.forEach(h -> sb.append(h).append('\n'));
        String text = sb.toString();
        return new SchemeResult("A-无压缩", EST.estimate(text), text.contains(KEY_KNOWLEDGE), text.contains(AUTH_BOUNDARY));
    }

    private static SchemeResult schemeB(String question, String schema, List<String> history, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(question).append('\n').append(schema).append('\n');
        history.forEach(h -> sb.append(h).append('\n'));
        String truncated = sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
        return new SchemeResult("B-简单截断", EST.estimate(truncated),
                truncated.contains(KEY_KNOWLEDGE), truncated.contains(AUTH_BOUNDARY));
    }

    private static SchemeResult schemeC(String question, List<String> history) {
        List<ContextSegment> segments = new ArrayList<>();
        segments.add(new ContextSegment("auth", ContextLayer.L1_RECENT, ContextPriority.HIGH,
                AUTH_BOUNDARY, false, false, true));
        int i = 0;
        for (String h : history) {
            boolean isKey = h.equals(KEY_KNOWLEDGE);
            segments.add(new ContextSegment("m" + (i++), ContextLayer.L1_RECENT,
                    isKey ? ContextPriority.CRITICAL : ContextPriority.LOW, h, isKey, false, false));
        }
        // 较小历史预算，制造真实丢弃压力；关键事实（CRITICAL / 授权）仍强制保留
        ContextBudget budget = new ContextBudget(50, QUESTION.length() / 4 + 60, 0, 300);
        LayeredContextResult r = new LayeredContextBuilder(EST).build("SYS", question, segments, budget);
        int retainedTokens = EST.estimate(r.systemContext()) + EST.estimate(r.userContext());
        boolean retainsKey = r.userContext().contains(KEY_KNOWLEDGE) || r.systemContext().contains(KEY_KNOWLEDGE);
        boolean retainsAuth = r.systemContext().contains(AUTH_BOUNDARY);
        return new SchemeResult("C-L1L4", retainedTokens, retainsKey, retainsAuth);
    }

    private static List<String> longHistory() {
        List<String> history = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            history.add("第" + i + "轮历史消息：包含大量与当前问题无关的上下文内容，用于模拟长对话并验证三种上下文方案的压缩与截断差异表现是否一致可靠");
        }
        return history;
    }
}
