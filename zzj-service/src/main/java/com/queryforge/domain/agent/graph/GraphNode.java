package com.queryforge.domain.agent.graph;

/**
 * 统一状态图节点（指南 §4.1）。节点层在现有 {@code RunStatus} 持久状态机之上补充分层契约，
 * 不引入外部 StateGraph 依赖（本环境不可解析 spring-ai-alibaba-graph-core，按指南 §5.2 回退）。
 */
public enum GraphNode {
    CREATED,
    ROUTING,
    PLANNING,
    /** P14-ARCH-01：六 Agent 主图节点（真实 StateGraph 节点 id 同名）。 */
    MANAGER,
    HITL,
    DATA_SCIENTIST,
    CODE_ASSISTANT,
    TOOL_ASSISTANT,
    DASHBOARD,
    HITL_GATE,
    WAITING_PLAN_REVIEW,
    CONTEXT_BUILDING,
    RUNNING,
    KNOWLEDGE,
    SQL_GENERATION,
    WAITING_SQL_APPROVAL,
    SQL_EXECUTION,
    TOOL_INVOCATION,
    CODE_ANALYSIS,
    REPORT_GENERATION,
    WAITING_CLARIFICATION,
    COMPLETED,
    FAILED,
    CANCELED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}
