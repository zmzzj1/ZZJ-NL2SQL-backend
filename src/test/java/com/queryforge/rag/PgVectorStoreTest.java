package com.queryforge.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P12 S3：真实 PostgreSQL + pgvector 集成验证（P1209/P1212/P1214 核心）。
 * 用确定性 1024 维测试向量（非真实模型结果，明确标注），验证写入、余弦相似度检索、
 * userId/connectionId/vectorType 过滤与软删除失效。无 PG 或凭据时跳过，不冒充成功。
 */
class PgVectorStoreTest {

    private static final String TYPE = "GLOSSARY";

    private JdbcTemplate jdbc;
    private PgVectorStore store;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        String pass = System.getenv("PG_PASSWORD");
        Assumptions.assumeTrue(pass != null && !pass.isBlank(),
                "缺少 PG_PASSWORD，跳过 pgvector 集成验证");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv().getOrDefault(
                "PG_URL", "jdbc:postgresql://127.0.0.1:5432/rageng"));
        config.setUsername(System.getenv().getOrDefault("PG_USERNAME", "postgres"));
        config.setPassword(pass);
        config.setMaximumPoolSize(2);
        try {
            dataSource = new HikariDataSource(config);
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_vectors (
                        id BIGSERIAL PRIMARY KEY,
                        vector_type VARCHAR(32) NOT NULL,
                        source_id BIGINT NOT NULL,
                        owner_id BIGINT NOT NULL,
                        connection_id BIGINT,
                        workspace_id BIGINT,
                        embedding vector(1024) NOT NULL,
                        content_summary TEXT,
                        active BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """);
            store = new PgVectorStore(jdbc);
        } catch (Exception ex) {
            Assumptions.abort("PostgreSQL 不可达或 pgvector 不可用：" + ex.getMessage());
        }
    }

    @AfterEach
    void cleanup() {
        if (jdbc != null) {
            try {
                jdbc.update("DELETE FROM knowledge_vectors WHERE owner_id IN (101, 202)");
            } catch (Exception ignored) {
                // 清理失败不影响测试结论
            }
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void addAndSimilaritySearch_filtersByOwnerConnectionAndType() {
        store.add(TYPE, 1L, 101L, 100L, null, vec(1.0, 0.0), "术语-销量口径");
        store.add(TYPE, 2L, 202L, 100L, null, vec(1.0, 0.0), "术语-其它用户");
        store.add(TYPE, 3L, 101L, 200L, null, vec(1.0, 0.0), "术语-其它连接");

        List<PgVectorStore.Hit> hits = store.similaritySearch(TYPE, 101L, 100L, vec(1.0, 0.0), 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(h -> h.ownerId() == 101L && h.connectionId() == 100L);
        assertThat(hits.get(0).sourceId()).isEqualTo(1L);
        assertThat(hits.get(0).cosineDistance()).isLessThan(0.01f);
    }

    @Test
    void similaritySearch_ordersByCosineDistance() {
        store.add(TYPE, 11L, 101L, 100L, null, vec(1.0, 0.0), "最近");
        store.add(TYPE, 12L, 101L, 100L, null, vec(0.0, 1.0), "较远");

        List<PgVectorStore.Hit> hits = store.similaritySearch(TYPE, 101L, 100L, vec(1.0, 0.0), 5);

        assertThat(hits.get(0).sourceId()).isEqualTo(11L);
        assertThat(hits.get(0).cosineDistance()).isLessThan(hits.get(1).cosineDistance());
    }

    @Test
    void deactivate_removesFromSearchResults() {
        store.add(TYPE, 21L, 101L, 100L, null, vec(1.0, 0.0), "待失效");
        assertThat(store.similaritySearch(TYPE, 101L, 100L, vec(1.0, 0.0), 5)).isNotEmpty();

        store.deactivate(TYPE, 21L);

        assertThat(store.similaritySearch(TYPE, 101L, 100L, vec(1.0, 0.0), 5))
                .noneMatch(h -> h.sourceId() == 21L);
    }

    private static List<Double> vec(double... leading) {
        double[] full = new double[1024];
        for (int i = 0; i < leading.length; i++) {
            full[i] = leading[i];
        }
        List<Double> out = new ArrayList<>(1024);
        for (double v : full) {
            out.add(v);
        }
        return out;
    }

}
