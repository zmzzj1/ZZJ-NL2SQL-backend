package com.queryforge.domain.agent.graph;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 状态图允许边（条件边）。所有真实状态迁移必须经 {@link #isAllowed} 校验，
 * 杜绝散落在 Service 中的任意状态跳转（指南 §4.1 / P1104）。
 */
public final class GraphTransition {

    private static final Map<GraphNode, Set<GraphNode>> EDGES = new HashMap<>();

    static {
        // AR-01-FB01：运行创建后尚未进入 ROUTING 即被取消（SSE 未开始/客户端立刻断开）。
        EDGES.put(GraphNode.CREATED, EnumSet.of(GraphNode.ROUTING, GraphNode.CANCELED));
        EDGES.put(GraphNode.ROUTING, EnumSet.of(
                GraphNode.PLANNING, GraphNode.KNOWLEDGE, GraphNode.WAITING_CLARIFICATION,
                GraphNode.TOOL_INVOCATION, GraphNode.COMPLETED, GraphNode.FAILED));
        EDGES.put(GraphNode.PLANNING, EnumSet.of(
                GraphNode.CONTEXT_BUILDING, GraphNode.WAITING_CLARIFICATION, GraphNode.COMPLETED, GraphNode.FAILED));
        EDGES.put(GraphNode.CONTEXT_BUILDING, EnumSet.of(GraphNode.RUNNING, GraphNode.FAILED));
        EDGES.put(GraphNode.RUNNING, EnumSet.of(
                GraphNode.SQL_GENERATION, GraphNode.TOOL_INVOCATION, GraphNode.CODE_ANALYSIS,
                GraphNode.REPORT_GENERATION, GraphNode.WAITING_CLARIFICATION,
                GraphNode.COMPLETED, GraphNode.FAILED));
        EDGES.put(GraphNode.KNOWLEDGE, EnumSet.of(GraphNode.COMPLETED, GraphNode.FAILED));
        EDGES.put(GraphNode.SQL_GENERATION, EnumSet.of(GraphNode.WAITING_SQL_APPROVAL, GraphNode.FAILED));
        EDGES.put(GraphNode.WAITING_SQL_APPROVAL, EnumSet.of(
                GraphNode.SQL_EXECUTION, GraphNode.CANCELED, GraphNode.FAILED));
        // AR-01-FB01：SQL 修复后回到待审批（不是跳过审批，而是换发新版本重新确认）。
        EDGES.put(GraphNode.SQL_EXECUTION, EnumSet.of(
                GraphNode.RUNNING, GraphNode.REPORT_GENERATION, GraphNode.CODE_ANALYSIS,
                GraphNode.TOOL_INVOCATION, GraphNode.WAITING_SQL_APPROVAL,
                GraphNode.COMPLETED, GraphNode.FAILED));
        EDGES.put(GraphNode.TOOL_INVOCATION, EnumSet.of(
                GraphNode.RUNNING, GraphNode.REPORT_GENERATION, GraphNode.CODE_ANALYSIS,
                GraphNode.COMPLETED, GraphNode.FAILED));
        EDGES.put(GraphNode.CODE_ANALYSIS, EnumSet.of(
                GraphNode.RUNNING, GraphNode.REPORT_GENERATION, GraphNode.COMPLETED, GraphNode.FAILED));
        EDGES.put(GraphNode.REPORT_GENERATION, EnumSet.of(GraphNode.COMPLETED, GraphNode.FAILED));
        EDGES.put(GraphNode.WAITING_CLARIFICATION, EnumSet.of(
                GraphNode.PLANNING, GraphNode.CANCELED, GraphNode.FAILED));
    }

    private GraphTransition() {
    }

    public static boolean isAllowed(GraphNode from, GraphNode to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.terminal()) {
            return false; // 终态不允许迟到回调覆写（P1104/P1119 硬门槛）
        }
        Set<GraphNode> allowed = EDGES.get(from);
        return allowed != null && allowed.contains(to);
    }

    /** 合法后继节点（用于测试与可视化）。 */
    public static Set<GraphNode> allowedNext(GraphNode from) {
        return EDGES.getOrDefault(from, EnumSet.noneOf(GraphNode.class));
    }
}
