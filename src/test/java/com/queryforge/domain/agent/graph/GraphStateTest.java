package com.queryforge.domain.agent.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GraphStateTest {

    @Test
    void transitions_allowLegalEdges_rejectIllegalAndTerminal() {
        assertThat(GraphTransition.isAllowed(GraphNode.CREATED, GraphNode.ROUTING)).isTrue();
        assertThat(GraphTransition.isAllowed(GraphNode.ROUTING, GraphNode.PLANNING)).isTrue();
        assertThat(GraphTransition.isAllowed(GraphNode.PLANNING, GraphNode.CONTEXT_BUILDING)).isTrue();
        assertThat(GraphTransition.isAllowed(GraphNode.CONTEXT_BUILDING, GraphNode.RUNNING)).isTrue();
        assertThat(GraphTransition.isAllowed(GraphNode.SQL_GENERATION, GraphNode.WAITING_SQL_APPROVAL)).isTrue();
        assertThat(GraphTransition.isAllowed(GraphNode.WAITING_SQL_APPROVAL, GraphNode.SQL_EXECUTION)).isTrue();
        assertThat(GraphTransition.isAllowed(GraphNode.WAITING_SQL_APPROVAL, GraphNode.CANCELED)).isTrue();

        // 非法跳跃：SQL 生成不能直接到报告，不能跳过审批；规划不能跳过上下文构建直达执行
        assertThat(GraphTransition.isAllowed(GraphNode.SQL_GENERATION, GraphNode.REPORT_GENERATION)).isFalse();
        assertThat(GraphTransition.isAllowed(GraphNode.PLANNING, GraphNode.RUNNING)).isFalse();
        // 终态无迟到回调
        assertThat(GraphTransition.isAllowed(GraphNode.COMPLETED, GraphNode.ROUTING)).isFalse();
        assertThat(GraphTransition.isAllowed(GraphNode.CANCELED, GraphNode.PLANNING)).isFalse();
    }

    @Test
    void graphState_fullComplexPath_isValidAndTracksRevision() {
        AgentGraphState state = AgentGraphState.initial(1L, 5L, null, 9L, 3L, 7L, "{}", 5, 12, 8000);
        assertThat(state.node()).isEqualTo(GraphNode.CREATED);
        assertThat(state.stateRevision()).isEqualTo(1);

        state.transitionTo(GraphNode.ROUTING)
                .transitionTo(GraphNode.PLANNING)
                .transitionTo(GraphNode.CONTEXT_BUILDING)
                .transitionTo(GraphNode.RUNNING)
                .transitionTo(GraphNode.SQL_GENERATION)
                .transitionTo(GraphNode.WAITING_SQL_APPROVAL)
                .transitionTo(GraphNode.SQL_EXECUTION)
                .transitionTo(GraphNode.REPORT_GENERATION)
                .transitionTo(GraphNode.COMPLETED);

        assertThat(state.node()).isEqualTo(GraphNode.COMPLETED);
        assertThat(state.completedNodes()).contains(
                "ROUTING", "PLANNING", "CONTEXT_BUILDING", "RUNNING", "SQL_GENERATION", "WAITING_SQL_APPROVAL",
                "SQL_EXECUTION", "REPORT_GENERATION");
        assertThat(state.stateRevision()).isEqualTo(10);
    }

    @Test
    void graphState_illegalTransition_throws() {
        AgentGraphState state = AgentGraphState.initial(1L, 5L, null, 9L, 3L, 7L, "{}", 5, 12, 8000);
        state.transitionTo(GraphNode.ROUTING).transitionTo(GraphNode.PLANNING);
        assertThatThrownBy(() -> state.transitionTo(GraphNode.REPORT_GENERATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态迁移");
    }

    @Test
    void graphState_terminalBlocksFurtherTransition() {
        AgentGraphState state = AgentGraphState.initial(1L, 5L, null, 9L, 3L, 7L, "{}", 5, 12, 8000);
        state.transitionTo(GraphNode.ROUTING).transitionTo(GraphNode.COMPLETED);
        assertThatThrownBy(() -> state.transitionTo(GraphNode.PLANNING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void graphState_jsonRoundTrip_preservesState() {
        ObjectMapper mapper = new ObjectMapper();
        AgentGraphState state = AgentGraphState.initial(11L, 5L, 2L, 9L, 3L, 7L, "{\"s\":1}", 5, 12, 8000);
        state.transitionTo(GraphNode.ROUTING).transitionTo(GraphNode.PLANNING);
        state.setApprovedSqlDigest("sha256:abc");
        state.recordToolCall("tool-9");

        String json = state.toJson(mapper);
        AgentGraphState restored = AgentGraphState.fromJson(mapper, json);

        assertThat(restored.runId()).isEqualTo(11L);
        assertThat(restored.node()).isEqualTo(GraphNode.PLANNING);
        assertThat(restored.approvedSqlDigest()).isEqualTo("sha256:abc");
        assertThat(restored.toolCallIds()).contains("tool-9");
        assertThat(restored.stateRevision()).isEqualTo(state.stateRevision());
    }

    @Test
    void graphState_incompatibleSnapshot_failsSafely() {
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> AgentGraphState.fromJson(mapper, "{not a valid graph state"))
                .isInstanceOf(IllegalStateException.class);
    }
}
