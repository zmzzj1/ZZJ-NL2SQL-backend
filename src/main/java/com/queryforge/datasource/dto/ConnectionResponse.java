package com.queryforge.datasource.dto;

/** 对外连接视图：不包含密码、密文或完整 JDBC URL。 */
public record ConnectionResponse(
        Long id,
        String name,
        String engine,
        String host,
        int port,
        String database,
        String username,
        boolean hasPassword
) {
}
