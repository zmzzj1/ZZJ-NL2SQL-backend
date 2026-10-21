package com.queryforge.datasource.repository;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.queryforge.datasource.ConnectionPolicyProperties;
import com.queryforge.datasource.TargetPoolProperties;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.shared.error.TargetConnectionException;
import com.queryforge.datasource.ConnectionTargetPolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按授权连接复用 Hikari 连接池；URL 只由已校验的 host/port/database 拼装，
 * 不接受客户端传入的任意 JDBC 参数。
 *
 * <p>P12 S5：池由 Caffeine 持有（键 = connectionId，有界容量 + 空闲过期），
 * 淘汰/失效时关闭旧池，避免连接与解密凭据长期滞留；连接配置变更（update/delete）
 * 由 Service 显式 {@link #evict(Long)}。池内连接统一
 * {@code SET SESSION TRANSACTION READ ONLY}，只读边界不因池化放松。
 */
@Component
public class TargetConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(TargetConnectionFactory.class);

    private final ConnectionPolicyProperties properties;
    private final ConnectionTargetPolicy policy;
    private final TargetPoolProperties poolProperties;
    private final Cache<Long, HikariDataSource> pools;

    public TargetConnectionFactory(
            ConnectionPolicyProperties properties,
            ConnectionTargetPolicy policy,
            TargetPoolProperties poolProperties) {
        this.properties = properties;
        this.policy = policy;
        this.poolProperties = poolProperties;
        this.pools = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(poolProperties.idleTimeoutMinutes()))
                .maximumSize(poolProperties.maxCachedPools())
                .removalListener((Long key, HikariDataSource pool, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (pool != null && !pool.isClosed()) {
                        try {
                            pool.close();
                            log.debug("target_pool_closed connectionId={} cause={}", key, cause);
                        } catch (Exception ex) {
                            log.warn("target_pool_close_failed connectionId={} type={}", key, ex.getClass().getSimpleName());
                        }
                    }
                })
                .build();
    }

    /**
     * 取出目标库连接（池化）。同一 connectionId 复用同一池；
     * 池不可用/连接失败时淘汰旧池并原样语义抛 {@link TargetConnectionException}。
     */
    public Connection open(ConnectionTarget target) {
        HikariDataSource pool = pool(target);
        try {
            Connection connection = pool.getConnection();
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException ex) {
            // 池已关闭或连接被拒：立即淘汰，下次请求重建，避免持续拿到坏池。
            evict(target.id());
            throw new TargetConnectionException("无法连接目标数据库", ex);
        }
    }

    /** 连接配置变更/删除/权限移除后调用：关闭旧池，凭据随池释放。 */
    public void evict(Long connectionId) {
        if (connectionId != null) {
            pools.invalidate(connectionId);
        }
    }

    /** 诊断/测试用：当前持有的池数量。 */
    public long cachedPoolCount() {
        return pools.estimatedSize();
    }

    private HikariDataSource pool(ConnectionTarget target) {
        Long id = target.id();
        HikariDataSource pool = pools.getIfPresent(id);
        if (pool == null || pool.isClosed()) {
            // 冷路径加锁建池，避免并发下重复建池导致泄漏（Caffeine get(key, loader)
            // 在竞争时可能多次调用 loader 并静默丢弃结果）。
            synchronized (this) {
                pool = pools.getIfPresent(id);
                if (pool == null || pool.isClosed()) {
                    pool = buildPool(target);
                    pools.put(id, pool);
                }
            }
        }
        return pool;
    }

    private HikariDataSource buildPool(ConnectionTarget target) {
        String host = policy.normalizeHost(target.host());
        int port = policy.validatePort(target.port());
        String database = policy.validateIdentifier(target.database(), "数据库名");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowMultiQueries=false"
                + "&connectTimeout=" + properties.connectTimeoutMs()
                + "&socketTimeout=" + properties.socketTimeoutMs();
        HikariConfig config = new HikariConfig();
        config.setPoolName("target-" + target.id());
        config.setJdbcUrl(url);
        config.setUsername(target.username());
        config.setPassword(target.password());
        config.setMaximumPoolSize(poolProperties.maxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(poolProperties.connectionTimeoutMs());
        config.setIdleTimeout(Duration.ofMinutes(poolProperties.idleTimeoutMinutes()).toMillis());
        config.setMaxLifetime(Duration.ofMinutes(poolProperties.maxLifetimeMinutes()).toMillis());
        config.setKeepaliveTime(Duration.ofSeconds(poolProperties.keepaliveSeconds()).toMillis());
        // 每个物理连接建立时即进入只读会话，与原 DriverManager 路径语义一致。
        config.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
        config.setRegisterMbeans(false);
        config.setLeakDetectionThreshold(60_000);
        return new HikariDataSource(config);
    }
}
