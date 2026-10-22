package com.zzj.gateway;

import java.time.Duration;
import java.util.function.Supplier;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P13-M2：网关只读复用核心服务的 Redis 会话存储。
 *
 * <p>与 {@code zzj-service} 的 {@code RedissonConfig} 保持同一版本与同一建连参数
 * （单点、最小空闲 1、相同重试），因此默认编解码一致，能读取同一条真实会话。
 * 只暴露响应式客户端：网关是 WebFlux，不能因会话校验阻塞 Netty 事件循环。
 *
 * <p>不引入 spring-data-redis / redisson-spring-boot-starter，避免把业务栈带进网关依赖树。
 */
@Configuration
@EnableConfigurationProperties(GatewayRedisConfig.GatewayRedisProperties.class)
public class GatewayRedisConfig {

    /**
     * 惰性单例：Redisson 建连是即时的（{@code Redisson.create} 会立即连 Redis），
     * 若放在 bean 初始化里，Redis 晚于网关启动就会让网关整体起不来；
     * 这里只在首次会话校验时创建，之后复用；创建失败即失败关闭，不影响匿名与健康检查。
     */
    @Bean
    public Supplier<RedissonReactiveClient> redissonReactiveClient(GatewayRedisProperties props) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + props.host() + ":" + props.port())
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(props.connectionPoolSize())
                .setRetryAttempts(props.retryAttempts())
                .setRetryInterval(props.retryIntervalMs());
        return new Supplier<>() {

            private volatile RedissonReactiveClient client;

            @Override
            public RedissonReactiveClient get() {
                RedissonReactiveClient local = client;
                if (local == null) {
                    synchronized (this) {
                        local = client;
                        if (local == null) {
                            RedissonClient created = Redisson.create(config);
                            local = created.reactive();
                            client = local;
                        }
                    }
                }
                return local;
            }
        };
    }

    /** 与 Service 的 app.redis.* 同名前缀，部署时可用同一套环境变量。 */
    @ConfigurationProperties(prefix = "app.redis")
    public record GatewayRedisProperties(String host, int port, int connectionPoolSize,
                                         int retryAttempts, int retryIntervalMs) {

        public GatewayRedisProperties {
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
                retryAttempts = 2;
            }
            if (retryIntervalMs <= 0) {
                retryIntervalMs = 200;
            }
        }
    }

    /** 会话校验读超时：超时按不可用处理，交回失败关闭逻辑；缺省 2s。 */
    @Bean
    public Duration gatewaySessionReadTimeout(
            @Value("${app.auth.session-read-timeout-ms:2000}") long timeoutMs) {
        return Duration.ofMillis(timeoutMs);
    }
}
