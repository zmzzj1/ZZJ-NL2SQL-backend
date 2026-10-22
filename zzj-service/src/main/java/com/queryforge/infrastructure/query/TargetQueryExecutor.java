package com.queryforge.infrastructure.query;

import com.queryforge.domain.datasource.ConnectionTarget;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.shared.error.TargetConnectionException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.SqlErrorClassifier;
import com.queryforge.domain.query.SqlExecutionException;
import com.queryforge.infrastructure.persistence.TargetQueryRepository;

/**
 * 目标库 JDBC 执行适配：在授权连接上执行已校验 SQL，并把底层异常映射为统一错误。
 *
 * <p>不感知用户、会话与历史，只做“执行 + 异常分类（是否可修复）”；连接解析、
 * Guard 校验、会话校验与历史记录在用例层 {@link SqlExecutionService}。
 */
@Service
public class TargetQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(TargetQueryExecutor.class);

    private final TargetQueryRepository repository;

    public TargetQueryExecutor(TargetQueryRepository repository) {
        this.repository = repository;
    }

    public QueryResultResponse execute(ConnectionTarget target, String sql, int maxRows, int timeoutSeconds) {
        try {
            return repository.query(target, sql, maxRows, timeoutSeconds);
        } catch (TargetConnectionException ex) {
            log.warn("query_connection_failed connectionId={} type={}",
                    target.id(), ex.getClass().getSimpleName());
            throw new ApiException(
                    ErrorCode.CONNECTION_FAILED,
                    HttpStatus.BAD_GATEWAY,
                    "无法连接该数据源，请检查连接配置",
                    ex);
        } catch (SQLTimeoutException ex) {
            throw new SqlExecutionException(
                    ErrorCode.SQL_EXECUTION_ERROR,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "查询超时，已被服务端终止",
                    null,
                    0,
                    false,
                    ex);
        } catch (SQLException ex) {
            log.warn("sql_execution_failed connectionId={} state={} code={}",
                    target.id(), ex.getSQLState(), ex.getErrorCode());
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
}
