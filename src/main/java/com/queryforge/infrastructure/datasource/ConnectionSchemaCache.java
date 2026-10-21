package com.queryforge.infrastructure.datasource;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.queryforge.domain.datasource.SchemaResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.queryforge.domain.datasource.SchemaCacheProperties;

/**
 * P12 S5：按 connectionId 的 Schema 有界缓存（DDL/外键元信息热点）。
 *
 * <p>只缓存"已通过归属校验"的连接 Schema：调用方先在 Service 层完成
 * {@code requireOwned} 再进入 {@link #get}，因此缓存不会成为越权读取的旁路。
 * 更新/删除连接时由 Service 显式 {@link #invalidate(Long)}；TTL 兜底。
 */
@Component
public class ConnectionSchemaCache {

    private final Cache<Long, SchemaResponse> cache;

    @Autowired
    public ConnectionSchemaCache(SchemaCacheProperties properties) {
        this(Duration.ofMinutes(properties.ttlMinutes()), properties.maxSize());
    }

    /** 测试/嵌入用途：直接指定 TTL 与容量。 */
    ConnectionSchemaCache(Duration ttl, long maxSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    /**
     * 命中直接返回；未命中按 loader 读取并写入缓存（loader 抛出的异常不缓存，原样上抛）。
     * 返回值对外按不可变快照对待：外层列表已拷贝，调用方不得修改。
     */
    public SchemaResponse get(Long connectionId, Function<Long, SchemaResponse> loader) {
        SchemaResponse loaded = cache.get(connectionId, loader);
        return loaded == null ? null : snapshot(loaded);
    }

    /** 连接配置变更/删除/权限移除后调用：旧 Schema 不得继续参与后续生成。 */
    public void invalidate(Long connectionId) {
        cache.invalidate(connectionId);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** 诊断/测试用：当前缓存条目数。 */
    public long estimatedSize() {
        return cache.estimatedSize();
    }

    private static SchemaResponse snapshot(SchemaResponse schema) {
        if (schema.tables().isEmpty()) {
            return schema;
        }
        return new SchemaResponse(schema.database(), List.copyOf(schema.tables()));
    }
}
