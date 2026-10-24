package com.queryforge.application.agent.runtime;

/**
 * P14-ARCH-01：六 Agent 主执行图的 Graph State 键名。
 *
 * <p>所有键都使用替换型（{@code KeyStrategy.REPLACE}）策略：节点返回的更新整体覆盖旧值，
 * 过期的 {@code nextRole/pendingApproval/failure} 等字段不会被残留污染后续判断。
 * 状态中只允许可序列化的身份引用、计划、错误信息与工件 ID；
 * 严禁存放数据库密码、LLM 客户端、DataSource、Future、SSE Sink、长表格或整个 HTML
 * （这些一律经由 {@link AgentRunTaskContext} 以 Graph Task Context 显式传递）。
 */
public final class AgentGraphStateKeys {

    private AgentGraphStateKeys() {
    }

    /** Manager 路由结果（AgentRoute 名）。 */
    public static final String ROUTE = "route";
    /** 结构化计划快照 JSON（{@link GraphPlanSnapshot} 的序列化形式，保证 checkpoint 可序列化）。 */
    public static final String PLAN_JSON = "planJson";
    /** 计划版本（每次 Planner 产出新计划 +1）。 */
    public static final String PLAN_VERSION = "planVersion";
    /** Manager 选定的下一个 Worker 角色（条件边读取）。 */
    public static final String DISPATCH_ROLE = "dispatchRole";
    /** 当前派发步骤的 MySQL stepId。 */
    public static final String CURRENT_STEP_ID = "currentStepId";
    /** 当前 Worker 尝试标识（每次派发生成，用于去重与审计）。 */
    public static final String ATTEMPT_ID = "attemptId";
    /** 最近一次 Worker 执行结果 JSON（{@link WorkerOutcome} 的序列化形式）。 */
    public static final String WORKER_OUTCOME_JSON = "workerOutcomeJson";
    /** 是否处于计划人工审核暂停（HITL_GATE 置位）。 */
    public static final String HITL_PENDING = "hitlPending";
    /** 当前待审核计划版本。 */
    public static final String HITL_VERSION = "hitlVersion";
    /** 待审核原因（Gate 判定理由，长度受限）。 */
    public static final String HITL_REASON = "hitlReason";
    /** 用户审核决定（true=批准 / false=拒绝），经 updateState 写入。 */
    public static final String HITL_APPROVED = "hitlApproved";
    /** 用户拒绝时提交的反馈（长度受限）。 */
    public static final String HITL_FEEDBACK = "hitlFeedback";
    /** 累计拒绝次数。 */
    public static final String HITL_REJECTION_COUNT = "hitlRejectionCount";
    /** 计划被拒绝后需要重规划（HITL 节点置位，Manager 消费）。 */
    public static final String REPLAN_REQUESTED = "replanRequested";
    /** 终态标记（COMPLETED/FAILED/CANCELED）。 */
    public static final String TERMINAL_STATUS = "terminalStatus";
    /** 终态失败码。 */
    public static final String FAILURE_CODE = "failureCode";
    /** 终态失败信息（脱敏、长度受限）。 */
    public static final String FAILURE_MESSAGE = "failureMessage";
    /** 最终答复（闲聊/澄清/知识/Dashboard 汇总）。 */
    public static final String FINAL_ANSWER = "finalAnswer";

    /** 全部状态键（构建 KeyStrategyFactory 时统一注册为替换型策略）。 */
    public static final String[] ALL_KEYS = {
            ROUTE, PLAN_JSON, PLAN_VERSION, DISPATCH_ROLE, CURRENT_STEP_ID, ATTEMPT_ID, WORKER_OUTCOME_JSON,
            HITL_PENDING, HITL_VERSION, HITL_REASON, HITL_APPROVED, HITL_FEEDBACK,
            HITL_REJECTION_COUNT, REPLAN_REQUESTED, TERMINAL_STATUS, FAILURE_CODE, FAILURE_MESSAGE,
            FINAL_ANSWER
    };
}
