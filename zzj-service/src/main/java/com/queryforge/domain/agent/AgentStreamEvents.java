package com.queryforge.domain.agent;

import java.util.List;

/**
 * Agent 流式事件负载。事件名与前端 api/agent.ts 及
 * frontend/docs/P4-FB04-api-contract.md 保持一致。
 */
public final class AgentStreamEvents {

    private AgentStreamEvents() {
    }

    public record RunStarted(Long runId, Long conversationId) {
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
}
