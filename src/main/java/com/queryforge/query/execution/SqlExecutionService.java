package com.queryforge.query.execution;
import com.queryforge.shared.QueryContext;
import com.queryforge.conversation.ConversationService;
import com.queryforge.datasource.ConnectionService;
import com.queryforge.query.history.QueryHistoryService;

import com.queryforge.query.execution.SqlLimits;
import com.queryforge.query.execution.ExecuteSqlRequest;
import com.queryforge.query.execution.QueryResultResponse;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.query.execution.SqlExecutionException;
import com.queryforge.shared.error.TargetConnectionException;
import com.queryforge.query.execution.TargetQueryRepository;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 在用户选中的授权连接上执行经校验的单条 SELECT，并把真实执行结果写入查询历史。
 * 被 SQL Guard 拒绝的语句不进入历史，避免把“尝试攻击”记成已执行查询。
 */
@Service
public class SqlExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionService.class);

    private final ConnectionService connectionService;
    private final TargetQueryRepository repository;
    private final ReadOnlySqlGuard guard;
    private final SqlLimits limits;
    private final ConversationService conversationService;
    private final QueryHistoryService historyService;

    public SqlExecutionService(
            ConnectionService connectionService,
            TargetQueryRepository repository,
            ReadOnlySqlGuard guard,
            SqlLimits limits,
            ConversationService conversationService,
            QueryHistoryService historyService) {
        this.connectionService = connectionService;
        this.repository = repository;
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
        try {
            QueryResultResponse result = repository.query(
                    target, sql, limits.maxRows(), limits.queryTimeoutSeconds());
            historyService.recordSuccess(context, target, sql, elapsedMs(startedAt), result);
            return result;
        } catch (TargetConnectionException ex) {
            historyService.recordFailure(context, target, sql, elapsedMs(startedAt),
                    ErrorCode.CONNECTION_FAILED, "无法连接该数据源，请检查连接配置");
            log.warn("query_connection_failed connectionId={} type={}",
                    request.connectionId(), ex.getClass().getSimpleName());
            throw new ApiException(
                    ErrorCode.CONNECTION_FAILED,
                    HttpStatus.BAD_GATEWAY,
                    "无法连接该数据源，请检查连接配置",
                    ex);
        } catch (SQLTimeoutException ex) {
            historyService.recordFailure(context, target, sql, elapsedMs(startedAt),
                    ErrorCode.SQL_EXECUTION_ERROR, "查询超时，已被服务端终止");
            throw new SqlExecutionException(
                    ErrorCode.SQL_EXECUTION_ERROR,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "查询超时，已被服务端终止",
                    null,
                    0,
                    false,
                    ex);
        } catch (SQLException ex) {
            historyService.recordFailure(context, target, sql, elapsedMs(startedAt),
                    ErrorCode.SQL_EXECUTION_ERROR, "SQL 执行失败，请检查表名、列名与语法");
            log.warn("sql_execution_failed connectionId={} state={} code={}",
                    request.connectionId(), ex.getSQLState(), ex.getErrorCode());
            throw new SqlExecutionException(
                    ErrorCode.SQL_EXECUTION_ERROR,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SQL 执行失败，请检查表名、列名与语法",
                    ex.getSQLState(),
                    ex.getErrorCode(),
                    SqlErrorClassifier.isFixable(ex.getSQLState(), ex.getErrorCode()),
                    ex);
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
