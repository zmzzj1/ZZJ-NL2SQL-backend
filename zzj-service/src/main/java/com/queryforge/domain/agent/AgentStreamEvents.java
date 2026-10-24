package com.queryforge.domain.agent;

import java.util.List;

/**
 * Agent 流式事件负载。事件名与前端 api/agent.ts 及
 * frontend/docs/P4-FB04-api-contract.md 保持一致。
 */
public final class AgentStreamEvents {

    private AgentStreamEvents() {
    }

    /**
     * 运行启动：真实 runId、conversationId 与 Graph threadId（同 threadId 恢复的定位键；
     * 浏览器端不能直接凭 threadId 绕过服务端身份验证）。
     */
    public record RunStarted(Long runId, Long conversationId, String threadId) {
    }

    public record PlanStep(Long stepId, int sequence, String objective) {
    }

    public record PlanReady(Long runId, List<PlanStep> steps) {
    }

    public record StepStarted(Long stepId, int sequence) {
    }

    public record StepSqlReady(Long stepId, String sql, int sqlVersion) {
    }

    public record ApprovalRequired(Long stepId, int sqlVersion) {
    }

    public record StepRepairing(Long stepId, int attempt, String reasonCode) {
    }

    public record StepSucceeded(Long stepId, int rowCount, boolean truncated, String summary) {
    }

    public record StepFailed(Long stepId, String code, String message) {
    }

    public record RunCompleted(Long runId, String summary) {
    }

    public record RunFailed(Long runId, String code, String message) {
    }

    public record RunCanceled(Long runId) {
    }

    public record AgentRouted(Long runId, String route, String reasonCode) {
    }

    public record AgentStarted(Long runId, String role, String activityId) {
    }

    public record AgentCompleted(Long runId, String role, String activityId, String status) {
    }

    public record KnowledgeSourceView(Long id, String sourceType, String title, int version) {
    }

    public record KnowledgeRetrieved(Long runId, List<KnowledgeSourceView> sources) {
    }

    public record ContextPrepared(Long runId, int usedTurns, boolean hasSummary) {
    }

    public record ClarifyRequired(Long runId, String message) {
    }

    public record SkillSelectedView(Long skillId, Long skillVersionId, int versionNo) {
    }

    /** 记录本轮选中的 Skill 及其版本（不含模板全文）。 */
    public record SkillSelected(Long runId, List<SkillSelectedView> skills) {
    }

    /** 记录本轮解析的 Agent 配置摘要（不含敏感提示词全文）。 */
    public record ConfigResolved(Long runId, Long agentId, Long agentVersionId, boolean ragEnabled, boolean memoryEnabled) {
    }

    /** 记录本轮注入的长期记忆条数（不含记忆正文）。 */
    public record MemoryRetrieved(Long runId, long count) {
    }

    /** 进入某个统一状态图节点（指南 §8）。 */
    public record NodeStarted(Long runId, String node, String revision) {
    }

    /** 离开某个统一状态图节点（指南 §8）。 */
    public record NodeCompleted(Long runId, String node, String revision) {
    }

    /** 节点停留于等待 SQL 审批（与 approval.required 互补，表达图层等待语义）。 */
    public record NodeWaitingApproval(Long runId, String node) {
    }

    /** 分层上下文压缩完成，附带脱敏元数据（指南 §7.2 / P1126）。 */
    public record ContextCompacted(Long runId, int inputTokensEstimate, int retainedSources,
                                   int droppedSegments, int historyBudget, String layersUsed, String contextHash) {
    }

    /** 本轮为某步骤选定的专业角色（指南 §6 / P1106）。 */
    public record AgentSelected(Long runId, int sequence, String objective, String role) {
    }

    /**
     * P14-A：显式工具单次执行的受限结果摘要（脱敏/截断，不回传工具完整输出原文）。
     * 与步骤详情保持一致，不伪造 SQL。
     */
    public record ToolResult(Long runId, Long stepId, String toolName, String status, String summary) {
    }

    /**
     * P14-B：复杂 SQL 多候选选优的脱敏摘要。仅在成功选出 SQL 后、{@code step.sql_ready}
     * 之前发送；{@code mode=SINGLE} 时不发送。只回传计数与分值，不包含候选原文、
     * Schema 原文、Token 或模型推理过程。
     */
    public record SqlCandidatesEvaluated(
            Long stepId,
            String mode,
            int totalCandidates,
            int validCandidates,
            int selectedCandidateIndex,
            double ruleScore,
            double llmScore,
            double compositeScore,
            boolean ruleFallback
    ) {
    }

    /**
     * P14-C：Code 步骤开始隔离 Python 分析（真实工件落库前只表示“开始”，不代表成功）。
     */
    public record AnalysisStarted(Long runId, Long stepId, String role) {
    }

    /**
     * P14-C：分析工件真实落库后发送。chartCount/summary/durationMs 均来自真实执行结果，
     * 不包含 stdout/stderr 原文、凭据或宿主路径。
     */
    public record AnalysisSucceeded(Long runId, Long stepId, Long analysisRunId, int chartCount,
                                    String summary, long durationMs) {
    }

    /** P14-C：分析真实失败（沙箱不可用/退出码非零/输出非法/超时/预算不足等）。 */
    public record AnalysisFailed(Long runId, Long stepId, String code, String message) {
    }

    /** P14-C：报告真实写入 reports 表后发送，reportId 可经详情接口鉴权访问。 */
    public record ReportReady(Long runId, Long stepId, Long reportId, String title) {
    }

    // ---------------------------------------------------------------- P14-D：计划级 HITL 审核事件

    /** 计划级 Gate 评估结果（无论是否需要人工审核都如实记录判定）。 */
    public record PlanReviewEvaluated(Long runId, boolean required, String reasonCode, boolean modelEvaluated) {
    }

    /** 进入计划审核等待：checkpoint 与待审核会话都已持久化后发送，流随后自然结束（不发送 run.completed）。 */
    public record PlanReviewRequired(Long runId, int hitlVersion, String reason, int rejectionCount,
                                    List<PlanReviewStep> steps) {
    }

    /** 服务端已成功领取/记录审核决定（批准或拒绝）。 */
    public record PlanReviewDecided(Long runId, int hitlVersion, boolean approved, int rejectionCount) {
    }

    /** 旧计划已安全替换并持久化新计划（拒绝后重规划）。 */
    public record PlanReplanned(Long runId, int newHitlVersion, int rejectionCount, List<PlanReviewStep> steps) {
    }

    /** 无法进行审核恢复或重规划时，准确错误（不能覆盖真实已终结 Run）。 */
    public record PlanReviewFailed(Long runId, String code, String message) {
    }

    /** 计划审核可展示的步骤摘要（禁止夹带 SQL 候选、凭据或内部 Prompt）。 */
    public record PlanReviewStep(Long stepId, int sequence, String objective, String agentRole, Integer dependsOn) {
    }

    /**
     * P14-E：MCP 工具参数经 LLM 自动修复的轻量元数据（不含修正后的参数原文）。
     * 仅用于让前端提示“参数已被自动修正”，diagnostic 为触发的校验错误摘要。
     */
    public record ToolRepair(Long runId, Long stepId, String toolName, int attempt, String diagnostic) {
    }

    /**
     * P14-ARCH-01：Worker Loop 阶段提示（Thinking/Review/Act/Verify 真实推进）。
     * phase 取值：thinking/acting/verified/verify_failed；不含模型推理原文与 Prompt。
     */
    public record WorkerPhase(Long runId, Long stepId, String role, String phase, String attemptId, int attempt) {
    }

    /**
     * P14-ARCH-01：In-JVM 消息总线派发提示（OFF/BYPASS/SWITCH 真实模式与关联 ID）。
     * 仅表示任务分派/回执语义，不代表业务执行成功。
     */
    public record BusDispatched(Long runId, Long stepId, String role, String mode, String messageId,
                                String correlationId, String kind) {
    }
}
