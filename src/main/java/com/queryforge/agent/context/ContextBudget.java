package com.queryforge.agent.context;

/**
 * 上下文 Token 预算（指南 §7.2）。注入前计算：
 * 模型可用上下文 - 系统/Agent 策略 - 当前问题 - 已确认约束 - 工具/输出预留 = 可分配历史预算。
 */
public record ContextBudget(
        int systemTokens,
        int questionTokens,
        int reservedToolTokens,
        int maxTotalTokens
) {
    public ContextBudget {
        if (maxTotalTokens <= 0) {
            maxTotalTokens = 8000;
        }
        if (systemTokens < 0) {
            systemTokens = 0;
        }
        if (questionTokens < 0) {
            questionTokens = 0;
        }
        if (reservedToolTokens < 0) {
            reservedToolTokens = 0;
        }
    }

    /** 可分配给历史/检索上下文的预算；不允许为负。 */
    public int historyBudget() {
        return Math.max(0, maxTotalTokens - systemTokens - questionTokens - reservedToolTokens);
    }
}
