package com.queryforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.queryforge.infrastructure.rag.EmbeddingClient;
import com.queryforge.infrastructure.rag.PgVectorStore;
import com.queryforge.application.memory.MemoryService.Confidence;
import com.queryforge.application.memory.MemoryService.MemoryType;
import com.queryforge.application.memory.MemoryService.Scope;
import com.queryforge.application.workspace.WorkspaceService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.io.FileInputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import com.queryforge.application.memory.MemoryService;
import com.queryforge.domain.memory.LongTermMemory;

/**
 * P12 S3：长期 Memory 向量的真实集成验证（P1209/P1212/P1213/P1214 证据）。
 *
 * <p>真实 MySQL 管理库（{@code long_term_memories}）+ 真实 PostgreSQL pgvector（{@code knowledge_vectors}）。
 * Embedding 用确定性测试向量按主题词映射（<b>非真实模型结果，仅验证存储/检索/过滤语义</b>）：
 * 含“订单/交易/GMV” → 主题向量 e1；含“带宽/网络” → 主题向量 e2。
 * 查询词与记忆正文刻意不共享关键词（如查询“GMV 交易额” vs 记忆“订单口径”），
 * 只有向量通道能召回，用于证明语义检索真实生效而非关键词冒充。
 * 无本地凭据或服务不可达时跳过，不冒充成功。
 */
class MemoryVectorIntegrationTest {

    private static final String USER_EMAIL_PREFIX = "memvec-user-";
    private static final String TITLE_PREFIX = "memvec-";
    private static final long WORKSPACE_ID = 9200L;
    private static final int DIMS = 1024;

    private HikariDataSource mySqlDataSource;
    private HikariDataSource pgDataSource;
    private JdbcTemplate pgJdbc;
    private MemoryService service;
    private long ownerA;
    private long ownerB;

    @BeforeEach
    void setUp() {
        Properties local = loadProps("config/local.properties", "../config/local.properties");
        Assumptions.assumeTrue(local != null, "无 backend/config/local.properties，跳过");

        String managementUrl = firstNonBlank(
                System.getenv("MANAGEMENT_DB_URL"),
                local.getProperty("app.management.url"),
                "jdbc:mysql://127.0.0.1:3306/sql_platform"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        String username = firstNonBlank(
                System.getenv("MANAGEMENT_DB_USERNAME"), local.getProperty("app.management.username"));
        String password = firstNonBlank(
                System.getenv("MANAGEMENT_DB_PASSWORD"), local.getProperty("app.management.password"));
        Assumptions.assumeTrue(username != null && password != null, "缺少管理库账号，跳过");

        HikariConfig mySqlConfig = new HikariConfig();
        mySqlConfig.setJdbcUrl(managementUrl);
        mySqlConfig.setUsername(username);
        mySqlConfig.setPassword(password);
        mySqlConfig.setMaximumPoolSize(2);

        String pgPass = System.getenv("PG_PASSWORD");
        Assumptions.assumeTrue(pgPass != null && !pgPass.isBlank(),
                "缺少 PG_PASSWORD，跳过 Memory 向量集成验证");

        HikariConfig pgConfig = new HikariConfig();
        pgConfig.setJdbcUrl(System.getenv().getOrDefault(
                "PG_URL", "jdbc:postgresql://127.0.0.1:5432/rageng"));
        pgConfig.setUsername(System.getenv().getOrDefault("PG_USERNAME", "postgres"));
        pgConfig.setPassword(pgPass);
        pgConfig.setMaximumPoolSize(2);

        try {
            mySqlDataSource = new HikariDataSource(mySqlConfig);
            mySqlDataSource.getConnection().close();
            pgDataSource = new HikariDataSource(pgConfig);
            pgJdbc = new JdbcTemplate(pgDataSource);
            pgJdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            pgJdbc.execute("""
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
        } catch (Exception ex) {
            close(mySqlDataSource);
            close(pgDataSource);
            Assumptions.abort("MySQL 或 PostgreSQL 不可达：" + ex.getMessage());
        }

        MemoryRepository repository = new MemoryRepository(new JdbcTemplate(mySqlDataSource));
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(anyString())).thenAnswer(invocation -> topicVector(invocation.getArgument(0)));
        // 工作空间成员资格策略由 WorkspaceService 单测覆盖；本测试聚焦向量投影与作用域过滤。
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        service = new MemoryService(repository, workspaceService, embeddingClient, new PgVectorStore(pgJdbc));

        // owner_id 有指向 users 的外键：创建两个真实测试用户（级联清理，不影响既有用户）。
        JdbcTemplate mySqlJdbc = new JdbcTemplate(mySqlDataSource);
        ownerA = insertTestUser(mySqlJdbc, "a");
        ownerB = insertTestUser(mySqlJdbc, "b");
    }

    @AfterEach
    void tearDown() {
        if (mySqlDataSource != null) {
            try {
                JdbcTemplate jdbc = new JdbcTemplate(mySqlDataSource);
                // 删除测试用户即级联删除其记忆与指纹（fk_memories_owner / fk_mem_fp_owner ON DELETE CASCADE）。
                jdbc.update("DELETE FROM users WHERE email LIKE ?", USER_EMAIL_PREFIX + "%");
            } catch (RuntimeException ignored) {
                // 清理失败不影响断言结论
            }
        }
        if (pgJdbc != null) {
            try {
                pgJdbc.update("DELETE FROM knowledge_vectors WHERE vector_type = 'MEMORY' AND owner_id IN (?, ?)",
                        ownerA, ownerB);
            } catch (RuntimeException ignored) {
                // 清理失败不影响断言结论
            }
        }
        close(mySqlDataSource);
        close(pgDataSource);
    }

    @Test
    @DisplayName("P1213：个人记忆创建后语义可召回（查询与记忆无关键词重叠，仅向量通道命中）")
    void createAndSearch_personalMemory_semanticRetrieval() {
        LongTermMemory orders = service.create(ownerA, null, MemoryType.FACT, Scope.PERSONAL,
                TITLE_PREFIX + unique("订单口径"), "已支付且未取消的订单视为有效订单",
                "conv-it", Confidence.VERIFIED, null);
        service.create(ownerA, null, MemoryType.FACT, Scope.PERSONAL,
                TITLE_PREFIX + unique("带宽口径"), "机房网络带宽上限为 1Gbps",
                "conv-it", Confidence.VERIFIED, null);

        List<LongTermMemory> byOrderQuery = service.search(ownerA, null, Scope.PERSONAL, "GMV 交易额统计");
        List<LongTermMemory> byBandwidthQuery = service.search(ownerA, null, Scope.PERSONAL, "网络延迟问题");

        assertThat(byOrderQuery).isNotEmpty();
        assertThat(byOrderQuery.get(0).id()).isEqualTo(orders.id());
        assertThat(byBandwidthQuery).isNotEmpty();
        assertThat(byBandwidthQuery.get(0).title()).contains("带宽口径");
    }

    @Test
    @DisplayName("P1213/P1214：跨用户隔离——他人查询命中同一向量也拿不到本条记忆")
    void search_crossUserIsolated() {
        service.create(ownerA, null, MemoryType.FACT, Scope.PERSONAL,
                TITLE_PREFIX + unique("订单口径"), "已支付且未取消的订单视为有效订单",
                "conv-it", Confidence.VERIFIED, null);

        List<LongTermMemory> otherUser = service.search(ownerB, null, Scope.PERSONAL, "GMV 交易额统计");

        assertThat(otherUser).isEmpty();
    }

    @Test
    @DisplayName("P1212：停用后 MySQL 候选与 pgvector 投影同步失效，不再召回")
    void disable_removesFromVectorSearch() {
        LongTermMemory memory = service.create(ownerA, null, MemoryType.FACT, Scope.PERSONAL,
                TITLE_PREFIX + unique("订单口径"), "已支付且未取消的订单视为有效订单",
                "conv-it", Confidence.VERIFIED, null);
        assertThat(service.search(ownerA, null, Scope.PERSONAL, "GMV 交易额统计"))
                .extracting(LongTermMemory::id).contains(memory.id());

        service.disable(ownerA, memory.id());

        assertThat(service.search(ownerA, null, Scope.PERSONAL, "GMV 交易额统计")).isEmpty();
        Integer activeVectors = pgJdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_vectors WHERE vector_type = 'MEMORY' AND source_id = ? AND active = TRUE",
                Integer.class, memory.id());
        assertThat(activeVectors).isZero();
    }

    @Test
    @DisplayName("P1209/P1214：pgvector 中真实存在 MEMORY 类型投影行（owner/workspace 过滤字段正确）")
    void pg_containsActiveMemoryVectorProjection() {
        LongTermMemory memory = service.create(ownerA, null, MemoryType.FACT, Scope.PERSONAL,
                TITLE_PREFIX + unique("订单口径"), "已支付且未取消的订单视为有效订单",
                "conv-it", Confidence.VERIFIED, null);

        Integer rows = pgJdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_vectors"
                        + " WHERE vector_type = 'MEMORY' AND source_id = ? AND owner_id = ? AND workspace_id IS NULL"
                        + " AND embedding IS NOT NULL AND active = TRUE",
                Integer.class, memory.id(), ownerA);

        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("工作空间记忆仅在 WORKSPACE 作用域可召回，个人作用域不可见")
    void workspaceMemory_sharedWithinWorkspaceScope() {
        service.create(ownerA, WORKSPACE_ID, MemoryType.FACT, Scope.WORKSPACE,
                TITLE_PREFIX + unique("空间订单口径"), "空间共享：已支付且未取消的订单视为有效订单",
                "conv-it", Confidence.VERIFIED, null);

        List<LongTermMemory> workspaceScope = service.search(ownerA, WORKSPACE_ID, Scope.WORKSPACE, "GMV 交易额统计");
        List<LongTermMemory> personalScope = service.search(ownerA, null, Scope.PERSONAL, "GMV 交易额统计");

        assertThat(workspaceScope).extracting(LongTermMemory::scope).contains("WORKSPACE");
        assertThat(personalScope).isEmpty();
    }

    private static long insertTestUser(JdbcTemplate jdbc, String tag) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (email, display_name, password_hash) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, USER_EMAIL_PREFIX + tag + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + "@local.test");
            ps.setString(2, "记忆向量测试");
            ps.setString(3, "$2a$10$notarealhashnotarealhashnotarealhashnotarealhashno");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("测试用户创建后未返回主键");
        }
        return key.longValue();
    }

    // ---- 确定性测试向量（非真实模型结果）：主题词 → 单位向量 ----

    private static List<Double> topicVector(String text) {
        double[] full = new double[DIMS];
        if (containsAny(text, "订单", "交易", "GMV", "gmv")) {
            full[0] = 1.0;
        } else if (containsAny(text, "带宽", "网络")) {
            full[1] = 1.0;
        } else {
            full[0] = 0.5;
            full[1] = 0.5;
        }
        List<Double> out = new ArrayList<>(DIMS);
        for (double v : full) {
            out.add(v);
        }
        return out;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String unique(String base) {
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void close(HikariDataSource dataSource) {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static Properties loadProps(String... paths) {
        for (String path : paths) {
            File file = new File(path);
            if (file.exists()) {
                Properties properties = new Properties();
                try (FileInputStream in = new FileInputStream(file)) {
                    properties.load(in);
                    return properties;
                } catch (Exception ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
