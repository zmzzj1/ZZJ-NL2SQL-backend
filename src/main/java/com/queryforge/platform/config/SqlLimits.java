package com.queryforge.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行侧的硬限制。超时与最大行数由服务端强制，不依赖前端。
 */
@ConfigurationProperties(prefix = "app.sql")
public record SqlLimits(int maxRows, int queryTimeoutSeconds, String allowedSchema) {

    public SqlLimits {
        maxRows = maxRows <= 0 ? 200 : maxRows;
        queryTimeoutSeconds = queryTimeoutSeconds <= 0 ? 5 : queryTimeoutSeconds;
        allowedSchema = (allowedSchema == null || allowedSchema.isBlank()) ? "sql_demo" : allowedSchema.trim();
    }
}
