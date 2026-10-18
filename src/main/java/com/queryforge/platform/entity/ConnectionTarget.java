package com.queryforge.platform.entity;

/**
 * 解密后的目标库连接参数，只在请求处理期间存在于内存中。
 */
public record ConnectionTarget(
        Long id,
        String host,
        int port,
        String database,
        String username,
        String password
) {
}
