package com.queryforge.application.agent;

import com.queryforge.domain.query.QueryResultResponse;

/**
 * P14-C：一次审批执行的真实结果交接。{@link AgentStepEngine#executeAndContinue} 在
 * SQL 步骤成功落库后返回本载体，把同一用户/同一连接/同一 Run 的真实受限行数据交给
 * 下游 Code/Report 步骤；不落库、不入 SSE、不跨用户流转。
 */
public record ApprovedSqlOutcome(
        Long runId,
        Long stepId,
        int sqlVersion,
        Long userId,
        Long connectionId,
        Long conversationId,
        String database,
        QueryResultResponse result
) {
}
