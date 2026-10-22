package com.queryforge.domain.agent.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P14-A：为显式工具快捷路径补充唯一合法图边 ROUTING→TOOL_INVOCATION，
 * 不通过假的 PLANNING→SQL_GENERATION 绕图限制。
 */
@DisplayName("状态图合法边")
class GraphTransitionTest {

    @Test
    @DisplayName("ROUTING 可进入 TOOL_INVOCATION，且不破坏既有边")
    void routing_shouldAllowToolInvocation() {
        Set<GraphNode> allowed = GraphTransition.allowedNext(GraphNode.ROUTING);

        assertThat(allowed).contains(GraphNode.TOOL_INVOCATION);
        assertThat(allowed).containsAll(EnumSet.of(
                GraphNode.PLANNING, GraphNode.KNOWLEDGE, GraphNode.WAITING_CLARIFICATION,
                GraphNode.COMPLETED, GraphNode.FAILED));
        assertThat(GraphTransition.isAllowed(GraphNode.ROUTING, GraphNode.TOOL_INVOCATION)).isTrue();
    }

    @Test
    @DisplayName("TOOL_INVOCATION 可到 COMPLETED/FAILED，终态不可回退")
    void toolInvocation_shouldReachTerminal() {
        assertThat(GraphTransition.allowedNext(GraphNode.TOOL_INVOCATION))
                .containsAll(EnumSet.of(GraphNode.COMPLETED, GraphNode.FAILED));
        assertThat(GraphTransition.isAllowed(GraphNode.COMPLETED, GraphNode.TOOL_INVOCATION)).isFalse();
        assertThat(GraphTransition.isAllowed(GraphNode.FAILED, GraphNode.RUNNING)).isFalse();
    }

    @Test
    @DisplayName("ROUTING 不能直接跳到 SQL_GENERATION（不绕图限制）")
    void routing_shouldNotJumpToSqlGeneration() {
        assertThat(GraphTransition.isAllowed(GraphNode.ROUTING, GraphNode.SQL_GENERATION)).isFalse();
        assertThat(GraphTransition.isAllowed(GraphNode.PLANNING, GraphNode.SQL_GENERATION)).isFalse();
    }
}
