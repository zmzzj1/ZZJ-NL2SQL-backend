package com.queryforge.application.agent.runtime;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * P14-ARCH-01：Graph State 的类型化读写助手。
 * 节点 Action 只通过本类与 {@link OverAllState} 交互，避免散落的字符串键与隐式转换。
 */
public final class AgentGraphStateAccess {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentGraphStateAccess() {
    }

    /** 计划快照 → checkpoint JSON（只保存可序列化文本）。 */
    public static String planToJson(GraphPlanSnapshot plan) {
        try {
            return MAPPER.writeValueAsString(plan == null ? GraphPlanSnapshot.empty() : plan);
        } catch (IOException ex) {
            return "{\"answerable\":false,\"reason\":\"plan-serialize-failed\",\"steps\":[]}";
        }
    }

    /** checkpoint JSON → 计划快照（损坏时按空计划处理，不伪造步骤）。 */
    public static GraphPlanSnapshot planFromJson(String json) {
        if (json == null || json.isBlank()) {
            return GraphPlanSnapshot.empty();
        }
        try {
            return MAPPER.readValue(json, GraphPlanSnapshot.class);
        } catch (IOException ex) {
            return GraphPlanSnapshot.empty();
        }
    }

    /** Worker 结果 → checkpoint JSON。 */
    public static String outcomeToJson(WorkerOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(outcome);
        } catch (IOException ex) {
            return null;
        }
    }

    /** checkpoint JSON → Worker 结果。 */
    public static WorkerOutcome outcomeFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, WorkerOutcome.class);
        } catch (IOException ex) {
            return null;
        }
    }

    public static Map<String, Object> initialMap(Long runId) {
        Map<String, Object> initial = new HashMap<>();
        initial.put(AgentGraphStateKeys.ROUTE, null);
        initial.put(AgentGraphStateKeys.PLAN_JSON, planToJson(GraphPlanSnapshot.empty()));
        initial.put(AgentGraphStateKeys.PLAN_VERSION, 0);
        initial.put(AgentGraphStateKeys.DISPATCH_ROLE, null);
        initial.put(AgentGraphStateKeys.CURRENT_STEP_ID, null);
        initial.put(AgentGraphStateKeys.ATTEMPT_ID, null);
        initial.put(AgentGraphStateKeys.WORKER_OUTCOME_JSON, null);
        initial.put(AgentGraphStateKeys.HITL_PENDING, false);
        initial.put(AgentGraphStateKeys.HITL_VERSION, 0);
        initial.put(AgentGraphStateKeys.HITL_REASON, "");
        initial.put(AgentGraphStateKeys.HITL_APPROVED, null);
        initial.put(AgentGraphStateKeys.HITL_FEEDBACK, "");
        initial.put(AgentGraphStateKeys.HITL_REJECTION_COUNT, 0);
        initial.put(AgentGraphStateKeys.REPLAN_REQUESTED, false);
        initial.put(AgentGraphStateKeys.TERMINAL_STATUS, null);
        initial.put(AgentGraphStateKeys.FAILURE_CODE, null);
        initial.put(AgentGraphStateKeys.FAILURE_MESSAGE, null);
        initial.put(AgentGraphStateKeys.FINAL_ANSWER, null);
        return initial;
    }

    public static String route(OverAllState state) {
        return state.value(AgentGraphStateKeys.ROUTE, String.class).orElse(null);
    }

    public static GraphPlanSnapshot plan(OverAllState state) {
        return planFromJson(state.value(AgentGraphStateKeys.PLAN_JSON, String.class).orElse(null));
    }

    public static int planVersion(OverAllState state) {
        return state.value(AgentGraphStateKeys.PLAN_VERSION, Integer.class).orElse(0);
    }

    public static String dispatchRole(OverAllState state) {
        return state.value(AgentGraphStateKeys.DISPATCH_ROLE, String.class).orElse(null);
    }

    public static Long currentStepId(OverAllState state) {
        return state.value(AgentGraphStateKeys.CURRENT_STEP_ID, Long.class).orElse(null);
    }

    public static String attemptId(OverAllState state) {
        return state.value(AgentGraphStateKeys.ATTEMPT_ID, String.class).orElse(null);
    }

    public static WorkerOutcome workerOutcome(OverAllState state) {
        return outcomeFromJson(state.value(AgentGraphStateKeys.WORKER_OUTCOME_JSON, String.class).orElse(null));
    }

    public static boolean hitlPending(OverAllState state) {
        return state.value(AgentGraphStateKeys.HITL_PENDING, Boolean.class).orElse(false);
    }

    public static int hitlVersion(OverAllState state) {
        return state.value(AgentGraphStateKeys.HITL_VERSION, Integer.class).orElse(0);
    }

    public static Boolean hitlApproved(OverAllState state) {
        return state.value(AgentGraphStateKeys.HITL_APPROVED, Boolean.class).orElse(null);
    }

    public static String hitlFeedback(OverAllState state) {
        return state.value(AgentGraphStateKeys.HITL_FEEDBACK, String.class).orElse("");
    }

    public static int hitlRejectionCount(OverAllState state) {
        return state.value(AgentGraphStateKeys.HITL_REJECTION_COUNT, Integer.class).orElse(0);
    }

    public static boolean replanRequested(OverAllState state) {
        return state.value(AgentGraphStateKeys.REPLAN_REQUESTED, Boolean.class).orElse(false);
    }

    public static String terminalStatus(OverAllState state) {
        return state.value(AgentGraphStateKeys.TERMINAL_STATUS, String.class).orElse(null);
    }

    public static String failureCode(OverAllState state) {
        return state.value(AgentGraphStateKeys.FAILURE_CODE, String.class).orElse(null);
    }

    public static String failureMessage(OverAllState state) {
        return state.value(AgentGraphStateKeys.FAILURE_MESSAGE, String.class).orElse(null);
    }

    public static String finalAnswer(OverAllState state) {
        return state.value(AgentGraphStateKeys.FINAL_ANSWER, String.class).orElse(null);
    }
}
