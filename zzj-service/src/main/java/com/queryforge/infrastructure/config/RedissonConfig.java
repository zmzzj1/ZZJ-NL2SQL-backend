package com.queryforge.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P12 S4：Redisson 客户端（Redis 协调）。
 * 单节点模式，仅承载短期互斥/临时状态；业务事实仍以 MySQL 管理库为准。
 * Redisson 连接惰性，Redis 不可用时首次操作失败（fail closed，不降级）。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedissonProperties props) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + props.host() + ":" + props.port())
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(props.connectionPoolSize())
                .setRetryAttempts(props.retryAttempts())
                .setRetryInterval(props.retryIntervalMs());
        return Redisson.create(config);
    }

    @ConfigurationProperties(prefix = "app.redis")
    public record RedissonProperties(String host, int port, int connectionPoolSize,
                                     int retryAttempts, int retryIntervalMs) {

        public RedissonProperties {
            if (host == null || host.isBlank()) {
                host = "127.0.0.1";
            }
            if (port <= 0) {
                port = 6379;
            }
            if (connectionPoolSize <= 0) {
                connectionPoolSize = 8;
            }
            if (retryAttempts <= 0) {
                retryAttempts = 3;
            }
            if (retryIntervalMs <= 0) {
                retryIntervalMs = 200;
            }
        }
    }
}
