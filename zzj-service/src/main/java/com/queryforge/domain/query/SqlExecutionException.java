package com.queryforge.domain.query;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;

import org.springframework.http.HttpStatus;

/**
 * SQL 执行失败：在统一 ApiException 之外保留 SQLState 与是否可安全修复。
 * 只有语法、表名、列名类错误允许进入 SQL Fixer。
 */
public class SqlExecutionException extends ApiException {

    private final String sqlState;
    private final int vendorCode;
    private final boolean fixable;

    public SqlExecutionException(ErrorCode errorCode, HttpStatus status, String message,
                                 String sqlState, int vendorCode, boolean fixable, Throwable cause) {
        super(errorCode, status, message, cause);
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
        this.fixable = fixable;
    }

    public String getSqlState() {
        return sqlState;
    }

    public int getVendorCode() {
        return vendorCode;
    }

    public boolean isFixable() {
        return fixable;
    }
}
