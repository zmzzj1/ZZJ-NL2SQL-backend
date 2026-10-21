package com.queryforge.agent.context;

/**
 * 一个带元数据的上下文片段。来源可以是最近消息（L1）、结构化摘要（L2）、
 * 检索命中（L3）或紧急保留事实（L4）。
 *
 * <p>安全约束（指南 §7.2 / P1122）：
 * <ul>
 *   <li>{@code isApproval}=true 表示“待审批”状态，压缩时绝不能记为“已批准”。</li>
 *   <li>{@code isAuthorization}=true 表示权限/授权边界事实，须保留且在 L4 优先。</li>
 *   <li>{@code isConfirmed}=false 表示模型推测，摘要层须明确标注，不混入用户确认事实。</li>
 * </ul>
 */
public record ContextSegment(
        String sourceId,
        ContextLayer layer,
        ContextPriority priority,
        String text,
        boolean isConfirmed,
        boolean isApproval,
        boolean isAuthorization
) {
    public ContextSegment {
        if (text == null) {
            text = "";
        }
        if (sourceId == null) {
            sourceId = "anon";
        }
    }

    public int tokens(TokenEstimator estimator) {
        return estimator.estimate(text);
    }

    /** 是否为 L4 必须保留的关键事实：当前指令、审批状态、授权边界或用户确认约束。 */
    public boolean isCriticalFact() {
        return priority == ContextPriority.CRITICAL
                || isApproval
                || isAuthorization
                || (layer == ContextLayer.L1_RECENT && isConfirmed);
    }
}
