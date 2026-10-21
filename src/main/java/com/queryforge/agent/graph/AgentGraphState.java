package com.queryforge.agent.graph;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 有类型的运行状态（指南 §4.1）。唯一真实来源仍是 Agent Run，本对象是 Run 的快照/引用，
 * 持久化为 agent_runs.graph_state_json。状态迁移必须经 {@link GraphTransition} 校验。
 *
 * <p>访问器使用记录式命名（{@code runId()} 而非 {@code getRunId()}），故用字段可见性让 Jackson 直接序列化/反序列化字段。
 */
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class AgentGraphState {

    public static final String GRAPH_VERSION = "P11-GRAPH-1";

    private final long runId;
    private final Long ownerUserId;
    private final Long workspaceId;
    private final Long connectionId;
    private final Long agentId;
    private final Long agentVersionId;
    private String skillVersionRefs;
    private String currentNode;
    private final List<String> completedNodes = new ArrayList<>();
    private Long pendingStepId;
    private String approvedSqlDigest;
    private String contextSnapshotRef;
    private String modelPolicyRef;
    private final List<String> toolCallIds = new ArrayList<>();
    private int maxSteps;
    private int maxModelCalls;
    private int tokenBudget;
    private long stateRevision;
    private String terminalReason;

    private AgentGraphState(long runId, Long ownerUserId, Long workspaceId, Long connectionId,
                            Long agentId, Long agentVersionId) {
        this.runId = runId;
        this.ownerUserId = ownerUserId;
        this.workspaceId = workspaceId;
        this.connectionId = connectionId;
        this.agentId = agentId;
        this.agentVersionId = agentVersionId;
        this.currentNode = GraphNode.CREATED.name();
    }

    /** Jackson 反序列化用的无参构造。final 字段由 Jackson 通过字段反射写入。 */
    private AgentGraphState() {
        this.runId = 0L;
        this.ownerUserId = null;
        this.workspaceId = null;
        this.connectionId = null;
        this.agentId = null;
        this.agentVersionId = null;
        this.currentNode = GraphNode.CREATED.name();
    }

    public static AgentGraphState initial(long runId, Long ownerUserId, Long workspaceId,
                                          Long connectionId, Long agentId, Long agentVersionId,
                                          String skillVersionRefs, int maxSteps, int maxModelCalls,
                                          int tokenBudget) {
        AgentGraphState state = new AgentGraphState(runId, ownerUserId, workspaceId, connectionId,
                agentId, agentVersionId);
        state.skillVersionRefs = skillVersionRefs;
        state.maxSteps = maxSteps;
        state.maxModelCalls = maxModelCalls;
        state.tokenBudget = tokenBudget;
        state.stateRevision = 1;
        return state;
    }

    public GraphNode node() {
        return GraphNode.valueOf(currentNode);
    }

    /** 经 GraphTransition 校验的有类型迁移；非法或终态后迁移抛出 IllegalStateException。 */
    public AgentGraphState transitionTo(GraphNode next) {
        GraphNode from = node();
        if (!GraphTransition.isAllowed(from, next)) {
            throw new IllegalStateException("非法状态迁移: " + from + " -> " + next);
        }
        if (from != next) {
            completedNodes.add(from.name());
        }
        this.currentNode = next.name();
        this.stateRevision++;
        return this;
    }

    public void markTerminal(String reason) {
        if (!node().terminal()) {
            throw new IllegalStateException("仅终态可设置 terminalReason: " + node());
        }
        this.terminalReason = reason;
    }

    public void recordToolCall(String toolCallId) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            toolCallIds.add(toolCallId);
        }
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("图状态序列化失败", ex);
        }
    }

    public static AgentGraphState fromJson(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, AgentGraphState.class);
        } catch (JsonProcessingException ex) {
            // 旧快照无法迁移：显式安全失败（P1130），不冒充恢复成功
            throw new IllegalStateException("图状态反序列化失败（不兼容快照）", ex);
        }
    }

    public long runId() {
        return runId;
    }

    public Long ownerUserId() {
        return ownerUserId;
    }

    public Long workspaceId() {
        return workspaceId;
    }

    public Long connectionId() {
        return connectionId;
    }

    public Long agentId() {
        return agentId;
    }

    public Long agentVersionId() {
        return agentVersionId;
    }

    public String skillVersionRefs() {
        return skillVersionRefs;
    }

    public String currentNode() {
        return currentNode;
    }

    public List<String> completedNodes() {
        return List.copyOf(completedNodes);
    }

    public Long pendingStepId() {
        return pendingStepId;
    }

    public void setPendingStepId(Long pendingStepId) {
        this.pendingStepId = pendingStepId;
    }

    public String approvedSqlDigest() {
        return approvedSqlDigest;
    }

    public void setApprovedSqlDigest(String approvedSqlDigest) {
        this.approvedSqlDigest = approvedSqlDigest;
    }

    public String contextSnapshotRef() {
        return contextSnapshotRef;
    }

    public void setContextSnapshotRef(String contextSnapshotRef) {
        this.contextSnapshotRef = contextSnapshotRef;
    }

    public String modelPolicyRef() {
        return modelPolicyRef;
    }

    public void setModelPolicyRef(String modelPolicyRef) {
        this.modelPolicyRef = modelPolicyRef;
    }

    public List<String> toolCallIds() {
        return List.copyOf(toolCallIds);
    }

    public int maxSteps() {
        return maxSteps;
    }

    public int maxModelCalls() {
        return maxModelCalls;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public long stateRevision() {
        return stateRevision;
    }

    public String terminalReason() {
        return terminalReason;
    }
}
