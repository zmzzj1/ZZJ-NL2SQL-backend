package com.queryforge.infrastructure.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.queryforge.domain.datasource.SchemaResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.queryforge.domain.datasource.SchemaCacheProperties;

/**
 * P12 S5：连接 Schema 缓存单元验证（P1220）。
 *
 * <p>验证有界缓存的三个核心契约：重复访问复用（loader 只跑一次）、
 * 显式失效后重新读取、TTL 到期后自动过期；loader 异常不污染缓存。
 */
class ConnectionSchemaCacheTest {

    private final AtomicInteger loadCount = new AtomicInteger();

    private SchemaResponse schema(String database) {
        return new SchemaResponse(database, List.of(
                new SchemaResponse.SchemaTable("shop_orders", List.of(
                        new SchemaResponse.SchemaColumn("amount", "DECIMAL", false)))));
    }

    @Test
    @DisplayName("同一 connectionId 重复访问只加载一次（缓存命中）")
    void repeatAccess_loadsOnce() {
        ConnectionSchemaCache cache = new ConnectionSchemaCache(new SchemaCacheProperties(5, 100));

        SchemaResponse first = cache.get(7L, id -> {
            loadCount.incrementAndGet();
            return schema("sql_demo_a");
        });
        SchemaResponse second = cache.get(7L, id -> {
            loadCount.incrementAndGet();
            return schema("sql_demo_a");
        });

        assertThat(loadCount.get()).isEqualTo(1);
        assertThat(first.tables()).isEqualTo(second.tables());
        assertThat(cache.estimatedSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 connectionId 相互隔离，不串用表结构")
    void differentConnections_areIsolated() {
        ConnectionSchemaCache cache = new ConnectionSchemaCache(new SchemaCacheProperties(5, 100));

        SchemaResponse a = cache.get(1L, id -> schema("sql_demo_a"));
        SchemaResponse b = cache.get(2L, id -> schema("sql_demo_b"));

        assertThat(a.database()).isEqualTo("sql_demo_a");
        assertThat(b.database()).isEqualTo("sql_demo_b");
        assertThat(cache.estimatedSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidate 后重新加载（连接 DDL/配置变更语义）")
    void invalidate_forcesReload() {
        ConnectionSchemaCache cache = new ConnectionSchemaCache(new SchemaCacheProperties(5, 100));
        cache.get(7L, id -> {
            loadCount.incrementAndGet();
            return schema("sql_demo_a");
        });

        cache.invalidate(7L);

        assertThat(cache.estimatedSize()).isZero();
        cache.get(7L, id -> {
            loadCount.incrementAndGet();
            return schema("sql_demo_a");
        });
        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidateAll 清空全部条目")
    void invalidateAll_clearsEverything() {
        ConnectionSchemaCache cache = new ConnectionSchemaCache(new SchemaCacheProperties(5, 100));
        cache.get(1L, id -> schema("sql_demo_a"));
        cache.get(2L, id -> schema("sql_demo_b"));

        cache.invalidateAll();

        assertThat(cache.estimatedSize()).isZero();
    }

    @Test
    @DisplayName("TTL 到期后自动过期并重新加载")
    void ttlExpiry_reloadsAfterExpiry() throws Exception {
        ConnectionSchemaCache cache =
                new ConnectionSchemaCache(java.time.Duration.ofMillis(120), 100);
        cache.get(9L, id -> {
            loadCount.incrementAndGet();
            return schema("sql_demo_a");
        });
        assertThat(cache.estimatedSize()).isEqualTo(1);

        Thread.sleep(250);

        cache.get(9L, id -> {
            loadCount.incrementAndGet();
            return schema("sql_demo_a");
        });
        assertThat(loadCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("loader 抛异常时原样上抛且不写缓存")
    void loaderFailure_notCached() {
        ConnectionSchemaCache cache = new ConnectionSchemaCache(new SchemaCacheProperties(5, 100));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                cache.get(7L, id -> {
                    throw new IllegalStateException("target down");
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("target down");
        assertThat(cache.estimatedSize()).isZero();

        // 异常不污染：下次可正常加载。
        assertThat(cache.get(7L, id -> schema("sql_demo_a")).database()).isEqualTo("sql_demo_a");
        assertThat(cache.estimatedSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("返回快照不可变：修改上层列表不影响缓存内容")
    void returnedSnapshot_isDefensiveCopy() {
        ConnectionSchemaCache cache = new ConnectionSchemaCache(new SchemaCacheProperties(5, 100));
        cache.get(7L, id -> schema("sql_demo_a"));

        SchemaResponse snapshot = cache.get(7L, id -> schema("sql_demo_a"));
        // SchemaResponse.tables 已是 List.copyOf，任何写入尝试都应失败。
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                snapshot.tables().add(new SchemaResponse.SchemaTable("hack", List.of()))))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
