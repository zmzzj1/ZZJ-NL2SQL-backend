package com.queryforge.application.agent;

import com.queryforge.domain.query.QueryResultResponse;

/**
 * P14-C：非 SQL 步骤（Code/Report）的执行输入载体（私有，非外部 API）。
 * 权限/来源值全部由后端验证产生，不由模型或客户端填入：
 * <ul>
 *   <li>{@code queryResult} 非空 = 同一次审批响应内的真实行数据交接；</li>
 *   <li>仅有 {@code authorizedSourceRef} = 跨请求恢复：按已审批 SQL 重新鉴权执行取数；</li>
 *   <li>两者都缺失 = 没有可证实的真实数据来源，步骤必须明确失败。</li>
 * </ul>
 */
public record AgentStepExecutionInputs(
        Long runId,
        Long userId,
        Long workspaceId,
        Long conversationId,
        Long connectionId,
        Long sourceStepId,
        Integer approvedSqlVersion,
        QueryResultResponse queryResult,
        String authorizedSourceRef,
        String effectiveContext
) {

    public static AgentStepExecutionInputs empty() {
        return new AgentStepExecutionInputs(null, null, null, null, null, null, null, null, null, "");
    }

    /** 是否有可直接使用的真实行数据。 */
    public boolean hasQueryResult() {
        return queryResult != null;
    }

    /** 从审批执行结果构造（同请求内交接，refetched=false）。 */
    public static AgentStepExecutionInputs fromOutcome(ApprovedSqlOutcome outcome, Long workspaceId,
                                                       Long conversationId, String effectiveContext) {
        return new AgentStepExecutionInputs(
                outcome.runId(),
                outcome.userId(),
                workspaceId,
                conversationId != null ? conversationId : outcome.conversationId(),
                outcome.connectionId(),
                outcome.stepId(),
                outcome.sqlVersion(),
                outcome.result(),
                outcome.runId() + "/" + outcome.stepId() + "/v" + outcome.sqlVersion(),
                effectiveContext == null ? "" : effectiveContext);
    }
}
