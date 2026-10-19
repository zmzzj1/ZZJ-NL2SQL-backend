package com.queryforge.workflow;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 服务端 DAG 校验（不信任前端校验）：节点 ID 唯一、类型存在、边端点存在、无环、
 * 至少一条 Start→End 路径、节点/边数量上限、必填配置。拒绝时返回可理解的错误。
 */
@Component
public class WorkflowGraphValidator {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "START", "END", "SQL_AGENT", "PYTHON_ANALYSIS", "REPORT", "MCP_TOOL");
    private static final int MAX_NODES = 20;
    private static final int MAX_EDGES = 40;

    public void validate(WorkflowGraph graph) {
        List<WorkflowGraph.Node> nodes = graph.nodes() == null ? List.of() : graph.nodes();
        List<WorkflowGraph.Edge> edges = graph.edges() == null ? List.of() : graph.edges();
        if (nodes.isEmpty()) {
            throw invalid("工作流至少需要一个节点");
        }
        if (nodes.size() > MAX_NODES) {
            throw invalid("节点数超过上限 " + MAX_NODES);
        }
        if (edges.size() > MAX_EDGES) {
            throw invalid("边数超过上限 " + MAX_EDGES);
        }

        Set<String> ids = new HashSet<>();
        Map<String, WorkflowGraph.Node> byId = new HashMap<>();
        for (WorkflowGraph.Node node : nodes) {
            if (node.id() == null || node.id().isBlank()) {
                throw invalid("节点 ID 不能为空");
            }
            if (!ids.add(node.id())) {
                throw invalid("节点 ID 重复：" + node.id());
            }
            if (node.type() == null || !ALLOWED_TYPES.contains(node.type())) {
                throw invalid("未知节点类型：" + node.type());
            }
            byId.put(node.id(), node);
        }

        long startCount = nodes.stream().filter(node -> "START".equals(node.type())).count();
        long endCount = nodes.stream().filter(node -> "END".equals(node.type())).count();
        if (startCount != 1) {
            throw invalid("必须有且仅有一个 START 节点");
        }
        if (endCount != 1) {
            throw invalid("必须有且仅有一个 END 节点");
        }

        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : ids) {
            adjacency.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }
        for (WorkflowGraph.Edge edge : edges) {
            if (edge.from() == null || edge.to() == null || !byId.containsKey(edge.from()) || !byId.containsKey(edge.to())) {
                throw invalid("边引用了不存在的节点：" + edge.from() + " -> " + edge.to());
            }
            adjacency.get(edge.from()).add(edge.to());
            inDegree.merge(edge.to(), 1, Integer::sum);
        }

        if (hasCycle(adjacency, ids)) {
            throw invalid("工作流存在循环，必须是 DAG");
        }

        String startId = nodes.stream().filter(node -> "START".equals(node.type())).findFirst().orElseThrow().id();
        String endId = nodes.stream().filter(node -> "END".equals(node.type())).findFirst().orElseThrow().id();
        if (!reachable(adjacency, startId, endId)) {
            throw invalid("不存在从 Start 到 End 的路径");
        }
    }

    private static boolean hasCycle(Map<String, List<String>> adjacency, Set<String> ids) {
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        for (String id : ids) {
            if (dfs(id, adjacency, visited, onStack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dfs(String id, Map<String, List<String>> adjacency,
                               Set<String> visited, Set<String> onStack) {
        if (onStack.contains(id)) {
            return true;
        }
        if (visited.contains(id)) {
            return false;
        }
        visited.add(id);
        onStack.add(id);
        for (String next : adjacency.getOrDefault(id, List.of())) {
            if (dfs(next, adjacency, visited, onStack)) {
                return true;
            }
        }
        onStack.remove(id);
        return false;
    }

    private static boolean reachable(Map<String, List<String>> adjacency, String start, String end) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (current.equals(end)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current, List.of())) {
                stack.push(next);
            }
        }
        return false;
    }

    /** 拓扑排序：返回节点的顺序执行序列（Start→End）。 */
    public List<WorkflowGraph.Node> topoOrder(WorkflowGraph graph) {
        validate(graph);
        Map<String, WorkflowGraph.Node> byId = new HashMap<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            byId.put(node.id(), node);
        }
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowGraph.Node node : graph.nodes()) {
            inDegree.put(node.id(), 0);
            adjacency.put(node.id(), new ArrayList<>());
        }
        for (WorkflowGraph.Edge edge : graph.edges()) {
            adjacency.get(edge.from()).add(edge.to());
            inDegree.merge(edge.to(), 1, Integer::sum);
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String id : inDegree.keySet()) {
            if (inDegree.get(id) == 0) {
                queue.add(id);
            }
        }
        List<WorkflowGraph.Node> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(byId.get(id));
            for (String next : adjacency.get(id)) {
                int degree = inDegree.merge(next, -1, Integer::sum);
                if (degree == 0) {
                    queue.add(next);
                }
            }
        }
        return order;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.WORKFLOW_INVALID_GRAPH, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
