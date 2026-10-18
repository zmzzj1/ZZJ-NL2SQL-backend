package com.queryforge.platform.service;

import com.queryforge.platform.config.SqlLimits;
import com.queryforge.platform.dto.QueryResultResponse;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import com.queryforge.platform.repository.DemoQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 在固定演示只读数据源上执行经校验的单条 SELECT。
 */
@Service
public class SqlExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionService.class);

    private final DemoQueryRepository repository;
    private final ReadOnlySqlGuard guard;
    private final SqlLimits limits;

    public SqlExecutionService(DemoQueryRepository repository, ReadOnlySqlGuard guard, SqlLimits limits) {
        this.repository = repository;
        this.guard = guard;
        this.limits = limits;
    }

    public QueryResultResponse execute(String rawSql) {
        String sql = guard.validate(rawSql);
        try {
            return repository.query(sql, limits.maxRows(), limits.queryTimeoutSeconds());
        } catch (DataAccessException ex) {
            Throwable cause = ex.getMostSpecificCause();
            log.warn("sql_execution_failed type={} message={}", cause.getClass().getSimpleName(), cause.getMessage());
            throw translate(ex);
        }
    }

    /**
     * 区分「数据库连不上」「查询超时」「SQL 本身有误」，给前端可操作的提示。
     */
    private static ApiException translate(DataAccessException ex) {
        if (ex instanceof DataAccessResourceFailureException) {
            return new ApiException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "演示数据库暂不可用，请确认 MySQL 已启动",
                    ex);
        }
        if (ex instanceof QueryTimeoutException) {
            return new ApiException(
                    ErrorCode.SQL_EXECUTION_ERROR,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "查询超时，已被服务端终止",
                    ex);
        }
        return new ApiException(
                ErrorCode.SQL_EXECUTION_ERROR,
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SQL 执行失败，请检查表名、列名与语法",
                ex);
    }
}
