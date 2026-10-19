package com.queryforge.query.execution;

/**
 * 只把语法、表名、列名类错误视为可修复；
 * 权限、认证、连接、超时和 SQL Guard 拒绝都不进入 Fixer。
 */
final class SqlErrorClassifier {

    private static final String SYNTAX_CLASS = "42";

    private SqlErrorClassifier() {
    }

    static boolean isFixable(String sqlState, int vendorCode) {
        if (vendorCode == 1044 || vendorCode == 1045 || vendorCode == 1142 || vendorCode == 1227) {
            return false;
        }
        return sqlState != null && sqlState.startsWith(SYNTAX_CLASS);
    }
}
