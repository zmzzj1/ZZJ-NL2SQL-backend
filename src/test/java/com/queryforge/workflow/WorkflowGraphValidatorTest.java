package com.queryforge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("工作流 DAG 校验")
class WorkflowGraphValidatorTest {

    private final WorkflowGraphValidator validator = new WorkflowGraphValidator();

    private static WorkflowGraph validGraph() {
        return new WorkflowGraph(
                List.of(
                        new WorkflowGraph.Node("start", "START", "开始", null),
                        new WorkflowGraph.Node("sql", "SQL_AGENT", "SQL", null),
                        new WorkflowGraph.Node("analysis", "PYTHON_ANALYSIS", "分析", null),
                        new WorkflowGraph.Node("report", "REPORT", "报告", null),
                        new WorkflowGraph.Node("end", "END", "结束", null)),
                List.of(
                        new WorkflowGraph.Edge("start", "sql"),
                        new WorkflowGraph.Edge("sql", "analysis"),
                        new WorkflowGraph.Edge("analysis", "report"),
                        new WorkflowGraph.Edge("report", "end")));
    }

    @Test
    @DisplayName("合法 DAG 通过校验并得到正确拓扑顺序")
    void validate_shouldAcceptValidDag() {
        WorkflowGraph graph = validGraph();
        validator.validate(graph);
        List<String> order = validator.topoOrder(graph).stream().map(WorkflowGraph.Node::id).toList();
        assertThat(order).containsExactly("start", "sql", "analysis", "report", "end");
    }

    @Test
    @DisplayName("循环被拒绝")
    void validate_shouldRejectCycle() {
        WorkflowGraph graph = new WorkflowGraph(
                List.of(
                        new WorkflowGraph.Node("start", "START", "", null),
                        new WorkflowGraph.Node("a", "SQL_AGENT", "", null),
                        new WorkflowGraph.Node("b", "PYTHON_ANALYSIS", "", null),
                        new WorkflowGraph.Node("end", "END", "", null)),
                List.of(
                        new WorkflowGraph.Edge("start", "a"),
                        new WorkflowGraph.Edge("a", "b"),
                        new WorkflowGraph.Edge("b", "a"),
                        new WorkflowGraph.Edge("b", "end")));
        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_INVALID_GRAPH));
    }

    @Test
    @DisplayName("重复节点 ID 被拒绝")
    void validate_shouldRejectDuplicateNodeId() {
        WorkflowGraph graph = new WorkflowGraph(
                List.of(
                        new WorkflowGraph.Node("start", "START", "", null),
                        new WorkflowGraph.Node("start", "SQL_AGENT", "", null),
                        new WorkflowGraph.Node("end", "END", "", null)),
                List.of(
                        new WorkflowGraph.Edge("start", "end")));
        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_INVALID_GRAPH));
    }

    @Test
    @DisplayName("未知节点类型被拒绝")
    void validate_shouldRejectUnknownType() {
        WorkflowGraph graph = new WorkflowGraph(
                List.of(
                        new WorkflowGraph.Node("start", "START", "", null),
                        new WorkflowGraph.Node("weird", "UNKNOWN", "", null),
                        new WorkflowGraph.Node("end", "END", "", null)),
                List.of(
                        new WorkflowGraph.Edge("start", "weird"),
                        new WorkflowGraph.Edge("weird", "end")));
        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_INVALID_GRAPH));
    }

    @Test
    @DisplayName("缺 Start 或 End 被拒绝")
    void validate_shouldRejectMissingStartOrEnd() {
        WorkflowGraph graph = new WorkflowGraph(
                List.of(
                        new WorkflowGraph.Node("a", "SQL_AGENT", "", null),
                        new WorkflowGraph.Node("b", "REPORT", "", null)),
                List.of(new WorkflowGraph.Edge("a", "b")));
        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_INVALID_GRAPH));
    }

    @Test
    @DisplayName("边引用不存在的节点被拒绝")
    void validate_shouldRejectDanglingEdge() {
        WorkflowGraph graph = new WorkflowGraph(
                List.of(
                        new WorkflowGraph.Node("start", "START", "", null),
                        new WorkflowGraph.Node("end", "END", "", null)),
                List.of(new WorkflowGraph.Edge("start", "missing")));
        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_INVALID_GRAPH));
    }

    @Test
    @DisplayName("无 Start→End 路径被拒绝")
    void validate_shouldRejectDisconnectedEnd() {
        WorkflowGraph graph = new WorkflowGraph(
                List.of(
                        new WorkflowGraph.Node("start", "START", "", null),
                        new WorkflowGraph.Node("a", "SQL_AGENT", "", null),
                        new WorkflowGraph.Node("end", "END", "", null)),
                List.of(new WorkflowGraph.Edge("start", "a")));
        assertThatThrownBy(() -> validator.validate(graph))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_INVALID_GRAPH));
    }
}
