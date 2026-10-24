package com.queryforge.application.agent.runtime;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.queryforge.domain.model.ModelRouteContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * P14-ARCH-01：六 Agent 主执行图构建器（拓扑唯一事实来源）。
 *
 * <pre>
 * START → MANAGER ─(条件)→ PLANNER | DATA_SCIENTIST | CODE_ASSISTANT | TOOL_ASSISTANT | DASHBOARD | END
 * PLANNER → HITL_GATE ─(条件)→ HITL | MANAGER
 * HITL ─(条件)→ MANAGER | END
 * DATA_SCIENTIST / CODE_ASSISTANT / TOOL_ASSISTANT → MANAGER
 * DASHBOARD → MANAGER（汇总结果统一由 Manager 裁决终态，避免 Dashboard 失败悬挂）
 * </pre>
 *
 * <p>所有状态键使用替换型 KeyStrategy：节点返回的更新整体覆盖旧值，
 * 过期 {@code dispatchRole/pendingApproval/failure} 不会残留污染后续判断。
 */
@Component
public class AgentGraphFactory {

    /** Task Context 在 RunnableConfig metadata 中的键（进程内对象，不进入 checkpoint）。 */
    public static final String TASK_CONTEXT_KEY = "_zzj_agent_task_context_";

    private static final String GRAPH_NAME = "zzj-agent-runtime";

    private final AgentGraphNodes nodes;

    public AgentGraphFactory(AgentGraphNodes nodes) {
        this.nodes = nodes;
    }

    /** 构建未编译的 StateGraph（节点/边/条件边在此唯一声明）。 */
    public StateGraph buildGraph() throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        for (String key : AgentGraphStateKeys.ALL_KEYS) {
            strategies.put(key, KeyStrategy.REPLACE);
        }
        KeyStrategyFactory keyStrategyFactory = () -> strategies;
        StateGraph graph = new StateGraph(GRAPH_NAME, keyStrategyFactory);

        graph.addNode(AgentGraphNodes.NODE_MANAGER,
                wrap(nodes::manager));
        graph.addNode(AgentGraphNodes.NODE_PLANNER,
                wrap(nodes::planner));
        graph.addNode(AgentGraphNodes.NODE_HITL_GATE,
                wrap(nodes::hitlGate));
        graph.addNode(AgentGraphNodes.NODE_HITL,
                wrap(nodes::hitl));
        graph.addNode(AgentGraphNodes.NODE_DATA_SCIENTIST,
                wrap(nodes::dataScientist));
        graph.addNode(AgentGraphNodes.NODE_CODE_ASSISTANT,
                wrap(nodes::codeAssistant));
        graph.addNode(AgentGraphNodes.NODE_TOOL_ASSISTANT,
                wrap(nodes::toolAssistant));
        graph.addNode(AgentGraphNodes.NODE_DASHBOARD,
                wrap(nodes::dashboard));

        graph.addEdge(START, AgentGraphNodes.NODE_MANAGER);

        graph.addConditionalEdges(AgentGraphNodes.NODE_MANAGER, managerEdge(), Map.of(
                AgentGraphNodes.NODE_PLANNER, AgentGraphNodes.NODE_PLANNER,
                AgentGraphNodes.NODE_DATA_SCIENTIST, AgentGraphNodes.NODE_DATA_SCIENTIST,
                AgentGraphNodes.NODE_CODE_ASSISTANT, AgentGraphNodes.NODE_CODE_ASSISTANT,
                AgentGraphNodes.NODE_TOOL_ASSISTANT, AgentGraphNodes.NODE_TOOL_ASSISTANT,
                AgentGraphNodes.NODE_DASHBOARD, AgentGraphNodes.NODE_DASHBOARD,
                END, END));

        graph.addEdge(AgentGraphNodes.NODE_PLANNER, AgentGraphNodes.NODE_HITL_GATE);

        graph.addConditionalEdges(AgentGraphNodes.NODE_HITL_GATE, hitlGateEdge(), Map.of(
                AgentGraphNodes.NODE_HITL, AgentGraphNodes.NODE_HITL,
                AgentGraphNodes.NODE_MANAGER, AgentGraphNodes.NODE_MANAGER));

        graph.addConditionalEdges(AgentGraphNodes.NODE_HITL, hitlEdge(), Map.of(
                AgentGraphNodes.NODE_MANAGER, AgentGraphNodes.NODE_MANAGER,
                END, END));

        graph.addEdge(AgentGraphNodes.NODE_DATA_SCIENTIST, AgentGraphNodes.NODE_MANAGER);
        graph.addEdge(AgentGraphNodes.NODE_CODE_ASSISTANT, AgentGraphNodes.NODE_MANAGER);
        graph.addEdge(AgentGraphNodes.NODE_TOOL_ASSISTANT, AgentGraphNodes.NODE_MANAGER);
        // Dashboard 结果（成功汇总或失败诊断）统一回 Manager 裁决终态：
        // 直接 END 会让 Graph 缺少终态标记，也会让 Dashboard 失败的运行悬挂在非终态。
        graph.addEdge(AgentGraphNodes.NODE_DASHBOARD, AgentGraphNodes.NODE_MANAGER);
        return graph;
    }

    /** Manager 条件边：终态/无派工 → END；否则按 dispatchRole 路由到对应节点。 */
    private static AsyncEdgeActionWithConfig managerEdge() {
        return (state, config) -> CompletableFuture.completedFuture(routeOf(state));
    }

    private static String routeOf(OverAllState state) {
        if (AgentGraphStateAccess.terminalStatus(state) != null) {
            return END;
        }
        String dispatchRole = AgentGraphStateAccess.dispatchRole(state);
        return dispatchRole == null ? END : dispatchRole;
    }

    /** HITL_GATE 条件边：需要人工审核 → HITL（编译期 interruptBefore 暂停）；否则回 Manager。 */
    private static AsyncEdgeActionWithConfig hitlGateEdge() {
        return (state, config) -> CompletableFuture.completedFuture(
                AgentGraphStateAccess.hitlPending(state)
                        ? AgentGraphNodes.NODE_HITL : AgentGraphNodes.NODE_MANAGER);
    }

    /** HITL 条件边：第三次拒绝已终结 → END；批准/拒绝重规划 → MANAGER。 */
    private static AsyncEdgeActionWithConfig hitlEdge() {
        return (state, config) -> CompletableFuture.completedFuture(
                AgentGraphStateAccess.terminalStatus(state) != null
                        ? END : AgentGraphNodes.NODE_MANAGER);
    }

    /**
     * 节点 Action 包装：从 RunnableConfig metadata 取 Task Context，设置/清理模型路由 ThreadLocal，
     * 保证身份与额度在图执行线程上正确可用且不串用户。
     */
    private static AsyncNodeActionWithConfig wrap(
            BiFunction<OverAllState, AgentRunTaskContext, Map<String, Object>> action) {
        return (state, config) -> {
            AgentRunTaskContext ctx = taskContextOf(config);
            ModelRouteContextHolder.Context previous = ModelRouteContextHolder.get();
            if (ctx != null && ctx.modelRouteContext() != null) {
                ModelRouteContextHolder.set(ctx.modelRouteContext());
            }
            try {
                return CompletableFuture.completedFuture(action.apply(state, ctx));
            } finally {
                if (ctx != null && ctx.modelRouteContext() != null) {
                    if (previous != null) {
                        ModelRouteContextHolder.set(previous);
                    } else {
                        ModelRouteContextHolder.clear();
                    }
                }
            }
        };
    }

    /** 从 RunnableConfig metadata 读取 Task Context（缺失 = 内部错误，节点自行失败收敛）。 */
    public static AgentRunTaskContext taskContextOf(RunnableConfig config) {
        return config.metadata(TASK_CONTEXT_KEY)
                .filter(AgentRunTaskContext.class::isInstance)
                .map(AgentRunTaskContext.class::cast)
                .orElse(null);
    }

    /** 构造携带 Task Context 的 RunnableConfig（每次 stream/resume 调用都重建）。 */
    public static RunnableConfig configWithContext(String threadId, AgentRunTaskContext ctx) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata(TASK_CONTEXT_KEY, ctx)
                .build();
    }
}
