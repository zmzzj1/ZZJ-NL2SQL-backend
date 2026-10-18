package com.queryforge.platform.service;

import com.queryforge.platform.config.SqlLimits;
import com.queryforge.platform.dto.ExecuteSqlRequest;
import com.queryforge.platform.dto.QueryResultResponse;
import com.queryforge.platform.entity.ConnectionTarget;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import com.queryforge.platform.exception.TargetConnectionException;
import com.queryforge.platform.repository.TargetQueryRepository;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 在用户选中的授权连接上执行经校验的单条 SELECT。
 */
@Service
public class SqlExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionService.class);

    private final ConnectionService connectionService;
    private final TargetQueryRepository repository;
    private final ReadOnlySqlGuard guard;
    private final SqlLimits limits;

    public SqlExecutionService(
            ConnectionService connectionService,
            TargetQueryRepository repository,
            ReadOnlySqlGuard guard,
            SqlLimits limits) {
        this.connectionService = connectionService;
        this.repository = repository;
        this.guard = guard;
        this.limits = limits;
    }

    public QueryResultResponse execute(Long userId, ExecuteSqlRequest request) {
        ConnectionTarget target = connectionService.target(userId, request.connectionId());
        String sql = guard.validate(request.sql(), target.database());
        try {
            return repository.query(target, sql, limits.maxRows(), limits.queryTimeoutSeconds());
        } catch (TargetConnectionException ex) {
            log.warn("query_connection_failed connectionId={} type={}",
                    request.connectionId(), ex.getClass().getSimpleName());
            throw new ApiException(
                    ErrorCode.CONNECTION_FAILED,
                    HttpStatus.BAD_GATEWAY,
                    "无法连接该数据源，请检查连接配置",
                    ex);
        } catch (SQLTimeoutException ex) {
            throw new ApiException(
                    ErrorCode.SQL_EXECUTION_ERROR,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "查询超时，已被服务端终止",
                    ex);
        } catch (SQLException ex) {
            log.warn("sql_execution_failed connectionId={} state={} code={}",
                    request.connectionId(), ex.getSQLState(), ex.getErrorCode());
            throw new ApiException(
                    ErrorCode.SQL_EXECUTION_ERROR,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "SQL 执行失败，请检查表名、列名与语法",
                    ex);
        }
    }
}
