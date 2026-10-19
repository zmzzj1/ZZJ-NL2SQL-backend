package com.queryforge.query.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行侧的硬限制。超时与最大行数由服务端强制，不依赖前端；
 * 允许访问的库由当前选中连接决定，不再全局固定。
 */
@ConfigurationProperties(prefix = "app.sql")
public record SqlLimits(int maxRows, int queryTimeoutSeconds) {

    public SqlLimits {
        maxRows = maxRows <= 0 ? 200 : maxRows;
        queryTimeoutSeconds = queryTimeoutSeconds <= 0 ? 5 : queryTimeoutSeconds;
    }
}
