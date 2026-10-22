package com.zzj.admin.identity;

import java.util.function.Supplier;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P13-M3A：只读复用核心服务的 Redis 会话存储。
 *
 * <p>与 Gateway 保持同一策略：
 * <ul>
 *   <li>不引入 redisson-spring-boot-starter（会拖入 spring-data-redis 与自动配置）；</li>
 *   <li>客户端<b>惰性创建</b>：Redis 晚于本进程启动或中途不可用，都不会让管理服务整体起不来，
 *       只会让管理查询返回 503（失败关闭）；</li>
 *   <li>只读：不写、不续期、不改 TTL，会话语义仍由核心服务决定。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AdminRedisConfig.AdminRedisProperties.class)
public class AdminRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminRedisConfig.class);

    @Bean(destroyMethod = "close")
    public LazyRedissonClient lazyRedissonClient(AdminRedisProperties properties) {
        return new LazyRedissonClient(properties);
    }

    /** 惰性 Redisson 客户端：首次读取时才建连，未创建过时 close 为空操作。 */
    public static final class LazyRedissonClient implements Supplier<RedissonClient>, AutoCloseable {

        private final AdminRedisProperties properties;
        private volatile RedissonClient client;
        private volatile boolean closed;

        private LazyRedissonClient(AdminRedisProperties properties) {
            this.properties = properties;
        }

        @Override
        public RedissonClient get() {
            if (client == null) {
                synchronized (this) {
                    if (client == null && !closed) {
                        Config cfg = config(properties);
                        log.info("admin_redis_connecting address={}:{}", properties.host(), properties.port());
                        client = Redisson.create(cfg);
                    }
                }
            }
            return client;
        }

        @Override
        public void close() {
            closed = true;
            RedissonClient created = client;
            if (created != null) {
                created.shutdown();
            }
        }

        private static Config config(AdminRedisProperties properties) {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://" + properties.host() + ":" + properties.port())
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(properties.connectionPoolSize())
                    .setRetryAttempts(properties.retryAttempts())
                    .setRetryInterval(properties.retryIntervalMs())
                    .setConnectTimeout(2000)
                    .setTimeout(properties.readTimeoutMs());
            return config;
        }
    }

    @ConfigurationProperties(prefix = "app.redis")
    public record AdminRedisProperties(String host, int port, int connectionPoolSize, int retryAttempts,
                                       int retryIntervalMs, int readTimeoutMs) {

        public AdminRedisProperties {
            if (host == null || host.isBlank()) {
                host = "127.0.0.1";
            }
            if (port <= 0) {
                port = 6379;
            }
            if (connectionPoolSize <= 0) {
                connectionPoolSize = 4;
            }
            if (retryAttempts <= 0) {
                retryAttempts = 1;
            }
            if (retryIntervalMs <= 0) {
                retryIntervalMs = 200;
            }
            if (readTimeoutMs <= 0) {
                readTimeoutMs = 2000;
            }
        }
    }
}
