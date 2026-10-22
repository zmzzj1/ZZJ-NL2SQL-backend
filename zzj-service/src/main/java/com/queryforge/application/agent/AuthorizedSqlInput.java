package com.queryforge.application.agent;

import com.queryforge.domain.query.QueryResultResponse;

/**
 * P14-C：服务端已验证的授权 SQL 输入。只能由 SQL 审批执行或合规恢复流程产生，
 * 字段全部来自服务端事实（Run/Step/用户/连接），绝不接受浏览器传入的虚构结果数组。
 *
 * @param refetched true 表示这是跨请求重新执行取到的较新数据（不是首次执行的静态快照），
 *                  分析与报告必须保留可追踪的再次获取说明。
 */
public record AuthorizedSqlInput(
        Long userId,
        Long runId,
        Long stepId,
        int sqlVersion,
        Long connectionId,
        Long conversationId,
        Long queryHistoryId,
        String sourceSql,
        String database,
        QueryResultResponse result,
        boolean refetched
) {
}
