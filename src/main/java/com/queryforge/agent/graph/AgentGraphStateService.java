package com.queryforge.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.agent.AgentRunRepository;
import com.queryforge.agent.AgentStreamEvents;
import com.queryforge.query.execution.SseEventWriter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * P11 A5/A6：统一状态图协调器。把运行节点迁移收敛到唯一的、经 {@link GraphTransition} 校验的入口，
 * 持久化到 {@code agent_graph_checkpoints}，并在每次迁移时发出 {@code node.started}/{@code node.completed} SSE，
 * 使前端与审计都能从真实轨迹核对 Graph（P1104）。
 *
 * <p>唯一真实来源仍是 Agent Run（{@code RunStatus}）；本服务只持有/快照有类型的 Graph 状态，
 * 不另起一套业务历史。恢复时从已确认的检查点继续，不根据旧结果重放模型/SQL/工具（P1115/P1119）。
 */
@Service
public class AgentGraphStateService {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphStateService.class);

    private static final int DEFAULT_TOKEN_BUDGET = 8000;
    private static final int DEFAULT_MAX_STEPS = 5;
    private static final int DEFAULT_MAX_MODEL_CALLS = 12;

    private final AgentRunRepository repository;
    private final ObjectMapper objectMapper;

    public AgentGraphStateService(AgentRunRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 运行创建时初始化 Graph 状态并落盘（CREATED）。 */
    public AgentGraphState initialize(long runId, Long ownerUserId, Long workspaceId, Long connectionId,
                                      Long agentId, Long agentVersionId, String skillVersionRefs, int tokenBudget) {
        AgentGraphState state = AgentGraphState.initial(
                runId, ownerUserId, workspaceId, connectionId, agentId, agentVersionId,
                skillVersionRefs, DEFAULT_MAX_STEPS, DEFAULT_MAX_MODEL_CALLS,
                tokenBudget > 0 ? tokenBudget : DEFAULT_TOKEN_BUDGET);
        persist(state);
        return state;
    }

    /** 进入目标节点：校验边、持久化、发出 node.started（及其前置 node.completed / 终态 node.completed）。 */
    public AgentGraphState enter(long runId, GraphNode node, SseEventWriter writer) {
        AgentGraphState current = loadOrCreate(runId);
        GraphNode from = current.node();
        if (from != node) {
            if (from != GraphNode.CREATED && !from.terminal() && writer != null) {
                emit(writer, new AgentStreamEvents.NodeCompleted(runId, from.name(), revision(current)));
            }
            current.transitionTo(node);
            persist(current);
        }
        if (writer != null) {
            emit(writer, new AgentStreamEvents.NodeStarted(runId, node.name(), revision(current)));
            if (node.terminal()) {
                emit(writer, new AgentStreamEvents.NodeCompleted(runId, node.name(), revision(current)));
            }
        }
        return current;
    }

    /** 仅在进入“等待 SQL 审批”节点时额外发出图级等待语义事件（与 approval.required 互补）。 */
    public void markWaitingApproval(long runId, GraphNode node, SseEventWriter writer) {
        if (writer != null && node == GraphNode.WAITING_SQL_APPROVAL) {
            emit(writer, new AgentStreamEvents.NodeWaitingApproval(runId, node.name()));
        }
    }

    /** 读取最近一次确认的检查点；无则返回空（恢复失败安全，不冒充成功，P1130）。 */
    public Optional<AgentGraphState> load(long runId) {
        return repository.findGraphCheckpointJson(runId)
                .map(json -> AgentGraphState.fromJson(objectMapper, json));
    }

    private AgentGraphState loadOrCreate(long runId) {
        return load(runId)
                .orElseGet(() -> AgentGraphState.initial(runId, null, null, null, null, null, "{}",
                        DEFAULT_MAX_STEPS, DEFAULT_MAX_MODEL_CALLS, DEFAULT_TOKEN_BUDGET));
    }

    private void persist(AgentGraphState state) {
        try {
            String json = state.toJson(objectMapper);
            repository.upsertGraphCheckpoint(state.runId(), json, state.stateRevision());
        } catch (RuntimeException ex) {
            log.warn("agent_graph_persist_failed runId={} message={}", state.runId(), ex.getMessage());
        }
    }

    private static String revision(AgentGraphState state) {
        return String.valueOf(state.stateRevision());
    }

    private static void emit(SseEventWriter writer, Object payload) {
        try {
            writer.event(nodeEventName(payload), payload);
        } catch (java.io.IOException ex) {
            log.debug("agent_graph_node_event_skipped runId={}", payload);
        }
    }

    private static String nodeEventName(Object payload) {
        if (payload instanceof AgentStreamEvents.NodeStarted) {
            return "node.started";
        }
        if (payload instanceof AgentStreamEvents.NodeCompleted) {
            return "node.completed";
        }
        if (payload instanceof AgentStreamEvents.NodeWaitingApproval) {
            return "node.waiting_approval";
        }
        return "node.event";
    }
}
