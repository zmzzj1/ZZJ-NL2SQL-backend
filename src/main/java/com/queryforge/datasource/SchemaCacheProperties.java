package com.queryforge.datasource;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P12 S5：连接 Schema（DDL/外键元信息）本地缓存参数。
 *
 * <p>键为 connectionId：同一有效配置重复访问直接复用，修改/删除连接时显式失效；
 * TTL 兜底防止目标库 DDL 变更后长时间读到旧结构。不缓存 SQL 结果与用户凭据。
 */
@ConfigurationProperties(prefix = "app.schema-cache")
public record SchemaCacheProperties(int ttlMinutes, int maxSize) {

    public SchemaCacheProperties {
        if (ttlMinutes <= 0) {
            ttlMinutes = 5;
        }
        if (maxSize <= 0) {
            maxSize = 500;
        }
    }
}
