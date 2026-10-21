package com.queryforge.datasource;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P12 S5：目标库 Hikari 连接池参数（按数据源配置复用，失效/淘汰时关闭旧池）。
 *
 * <p>与用户连接下的业务 SQL 边界不变：池内连接仍执行
 * {@code SET SESSION TRANSACTION READ ONLY}，读-only 约束不因池化放松。
 */
@ConfigurationProperties(prefix = "app.target-pool")
public record TargetPoolProperties(
        int maxPoolSize,
        int idleTimeoutMinutes,
        int maxLifetimeMinutes,
        int keepaliveSeconds,
        int connectionTimeoutMs,
        int maxCachedPools) {

    public TargetPoolProperties {
        if (maxPoolSize <= 0) {
            maxPoolSize = 4;
        }
        if (idleTimeoutMinutes <= 0) {
            idleTimeoutMinutes = 10;
        }
        if (maxLifetimeMinutes <= 0) {
            maxLifetimeMinutes = 30;
        }
        if (keepaliveSeconds <= 0) {
            keepaliveSeconds = 60;
        }
        if (connectionTimeoutMs <= 0) {
            connectionTimeoutMs = 5000;
        }
        if (maxCachedPools <= 0) {
            maxCachedPools = 64;
        }
    }
}
