package com.queryforge.domain.datasource;

/**
 * 解密后的目标库连接参数，只在请求处理期间存在于内存中。
 * name 用于会话/历史的数据源展示快照，不参与建连。
 */
public record ConnectionTarget(
        Long id,
        String name,
        String host,
        int port,
        String database,
        String username,
        String password
) {
}
