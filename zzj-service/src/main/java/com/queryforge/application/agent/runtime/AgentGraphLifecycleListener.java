package com.queryforge.application.agent.runtime;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.queryforge.application.agent.graph.AgentGraphStateService;
import com.queryforge.domain.agent.graph.GraphNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * P14-ARCH-01：真实 Graph 生命周期监听器（节点真实进入/完成的唯一事件源）。
 *
 * <p>把 CompiledGraph 的真实 node 进入/退出投影为：
 * <ul>
 *   <li>SSE {@code node.started}/{@code node.completed}（真实生命周期，不由 UI 猜测）；</li>
 *   <li>MySQL {@code agent_graph_checkpoints} 展示审计（{@link AgentGraphStateService}，
 *       仅展示/历史用途，不参与真实图的跳转决策）。</li>
 * </ul>
 *
 * <p>每次运行的身份/SSE 写入器从 {@link RunnableConfig} metadata 的 Task Context 取得，
 * 保证多 Run 并发时不串用户。
 */
@Component
public class AgentGraphLifecycleListener implements GraphLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphLifecycleListener.class);

    private final AgentGraphStateService graphStateService;

    public AgentGraphLifecycleListener(AgentGraphStateService graphStateService) {
        this.graphStateService = graphStateService;
    }

    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        enter(nodeId, config);
    }

    @Override
    public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        // 节点完成事件由下一个节点的 before（或终态收敛）统一投影，避免重复噪声。
    }

    @Override
    public void onError(String nodeId, Map<String, Object> state, Throwable ex, RunnableConfig config) {
        AgentRunTaskContext ctx = AgentGraphFactory.taskContextOf(config);
        if (ctx == null) {
            return;
        }
        try {
            graphStateService.enter(ctx.runId(), GraphNode.FAILED, ctx.writer());
        } catch (RuntimeException ignored) {
            log.debug("agent_graph_error_node_skipped runId={} node={}", ctx.runId(), nodeId);
        }
    }

    private void enter(String nodeId, RunnableConfig config) {
        AgentRunTaskContext ctx = AgentGraphFactory.taskContextOf(config);
        if (ctx == null) {
            return;
        }
        GraphNode node = toGraphNode(nodeId);
        if (node == null) {
            return;
        }
        try {
            graphStateService.enter(ctx.runId(), node, ctx.writer());
        } catch (RuntimeException ex) {
            // 展示审计失败不得影响真实图执行；SSE 事件由 enter 内部尽力发出。
            log.debug("agent_graph_node_audit_skipped runId={} node={} message={}",
                    ctx.runId(), nodeId, ex.getMessage());
        }
    }

    /** 真实图节点 id → 展示枚举（未知节点不投影）。 */
    static GraphNode toGraphNode(String nodeId) {
        return switch (nodeId) {
            case AgentGraphNodes.NODE_MANAGER -> GraphNode.MANAGER;
            case AgentGraphNodes.NODE_PLANNER -> GraphNode.PLANNING;
            case AgentGraphNodes.NODE_HITL_GATE -> GraphNode.HITL_GATE;
            case AgentGraphNodes.NODE_HITL -> GraphNode.HITL;
            case AgentGraphNodes.NODE_DATA_SCIENTIST -> GraphNode.DATA_SCIENTIST;
            case AgentGraphNodes.NODE_CODE_ASSISTANT -> GraphNode.CODE_ASSISTANT;
            case AgentGraphNodes.NODE_TOOL_ASSISTANT -> GraphNode.TOOL_ASSISTANT;
            case AgentGraphNodes.NODE_DASHBOARD -> GraphNode.DASHBOARD;
            default -> null;
        };
    }
}
