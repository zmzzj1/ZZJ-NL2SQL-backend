package com.queryforge.workflow;

import java.util.List;

/**
 * 工作流 DAG 定义。节点类型白名单：Start/SQL_AGENT/PYTHON_ANALYSIS/REPORT/END/MCP_TOOL。
 */
public record WorkflowGraph(List<Node> nodes, List<Edge> edges) {

    public record Node(String id, String type, String label, Config config) {
    }

    /** 节点配置（按类型携带必要参数）。 */
    public record Config(String objective, Long connectionId, String toolName, String chartType, String code) {
    }

    public record Edge(String from, String to) {
    }
}
