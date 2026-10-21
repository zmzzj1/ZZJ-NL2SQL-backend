package com.queryforge.application.query;
import com.queryforge.shared.QueryContext;
import com.queryforge.application.conversation.ConversationService;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.application.query.QueryHistoryService;

import com.queryforge.domain.query.SqlLimits;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.datasource.ConnectionTarget;
import com.queryforge.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.infrastructure.query.TargetQueryExecutor;

/**
 * 在用户选中的授权连接上执行经校验的单条 SELECT，并把真实执行结果写入查询历史。
 * 被 SQL Guard 拒绝的语句不进入历史，避免把“尝试攻击”记成已执行查询。
 *
 * <p>统一安全入口：连接解析 → Guard 校验 → 会话一致性校验 → JDBC 执行（委托
 * {@link TargetQueryExecutor}）→ 历史记录。Agent/Workflow/SQL Console 均走此入口，
 * 只读与幂等约束不因拆分而放松。
 */
@Service
public class SqlExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionService.class);

    private final ConnectionService connectionService;
    private final TargetQueryExecutor executor;
    private final ReadOnlySqlGuard guard;
    private final SqlLimits limits;
    private final ConversationService conversationService;
    private final QueryHistoryService historyService;

    public SqlExecutionService(
            ConnectionService connectionService,
            TargetQueryExecutor executor,
            ReadOnlySqlGuard guard,
            SqlLimits limits,
            ConversationService conversationService,
            QueryHistoryService historyService) {
        this.connectionService = connectionService;
        this.executor = executor;
        this.guard = guard;
        this.limits = limits;
        this.conversationService = conversationService;
        this.historyService = historyService;
    }

    public QueryResultResponse execute(Long userId, ExecuteSqlRequest request) {
        return execute(userId, request, null, null);
    }

    /** Agent 步骤执行：额外把 Run/Step 关联写入查询历史。 */
    public QueryResultResponse execute(Long userId, ExecuteSqlRequest request, Long runId, Long stepId) {
        ConnectionTarget target = connectionService.target(userId, request.connectionId());
        String sql = guard.validate(request.sql(), target.database());
        Long conversationId = conversationService.requireMatchingConversation(
                userId, request.conversationId(), request.connectionId());
        QueryContext context = new QueryContext(userId, conversationId, runId, stepId);
        long startedAt = System.nanoTime();
        QueryResultResponse result;
        try {
            result = executor.execute(target, sql, limits.maxRows(), limits.queryTimeoutSeconds());
        } catch (ApiException ex) {
            historyService.recordFailure(context, target, sql, elapsedMs(startedAt),
                    ex.getErrorCode(), ex.getMessage());
            throw ex;
        }
        historyService.recordSuccess(context, target, sql, elapsedMs(startedAt), result);
        return result;
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
