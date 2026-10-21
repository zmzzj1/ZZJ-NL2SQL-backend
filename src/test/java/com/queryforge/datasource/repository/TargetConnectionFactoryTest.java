package com.queryforge.datasource.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.datasource.ConnectionPolicyProperties;
import com.queryforge.datasource.ConnectionTargetPolicy;
import com.queryforge.datasource.TargetPoolProperties;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.shared.error.TargetConnectionException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P12 S5：目标库 Hikari 池化真实 MySQL 验证（P1221）。
 *
 * <p>用本地只读测试账号（demo_a_reader）验证：同一连接复用同一池、evict 后旧池关闭、
 * 池内连接仍是只读会话（写入被拒）。凭据缺失或库不可达时跳过，不冒充成功。
 */
class TargetConnectionFactoryTest {

    private TargetConnectionFactory factory;
    private ConnectionTarget target;

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("DEMO_DB_HOST", "127.0.0.1");
        String portText = System.getenv().getOrDefault("DEMO_DB_PORT", "3306");
        String database = System.getenv().getOrDefault("DEMO_A_DATABASE", "sql_demo_a");
        String username = System.getenv("DEMO_A_USERNAME");
        String password = System.getenv("DEMO_A_PASSWORD");
        Assumptions.assumeTrue(username != null && !username.isBlank()
                        && password != null && !password.isBlank(),
                "缺少 DEMO_A_USERNAME/DEMO_A_PASSWORD，跳过目标连接池验证");

        target = new ConnectionTarget(
                9001L, "池化测试", host, Integer.parseInt(portText), database, username, password);
        factory = new TargetConnectionFactory(
                new ConnectionPolicyProperties(null, null, 3000, 10000),
                new ConnectionTargetPolicy(new ConnectionPolicyProperties(null, null, 3000, 10000)),
                new TargetPoolProperties(2, 1, 5, 30, 5000, 8));

        // 先探一次连通性，不可达则跳过（不把环境问题记成失败）。
        try (Connection probe = factory.open(target)) {
            Assumptions.assumeTrue(probe.isValid(3), "目标库不可达，跳过");
        } catch (SQLException ex) {
            Assumptions.abort("目标库连接校验失败：" + ex.getClass().getSimpleName());
        } catch (RuntimeException ex) {
            Assumptions.abort("目标库不可达：" + ex.getClass().getSimpleName());
        }
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.evict(target.id());
        }
    }

    @Test
    @DisplayName("同一 connectionId 复用同一池：多次 open 只建一个池")
    void open_reusesSamePool() throws Exception {
        try (Connection first = factory.open(target);
             Connection second = factory.open(target)) {
            assertThat(first).isNotSameAs(second);
            assertThat(factory.cachedPoolCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("evict 后旧池关闭，下次 open 重建新池")
    void evict_closesPool_andRebuilds() throws Exception {
        try (Connection connection = factory.open(target)) {
            assertThat(connection.isValid(3)).isTrue();
        }
        assertThat(factory.cachedPoolCount()).isEqualTo(1);

        factory.evict(target.id());

        assertThat(factory.cachedPoolCount()).isZero();
        try (Connection again = factory.open(target)) {
            assertThat(again.isValid(3)).isTrue();
        }
        assertThat(factory.cachedPoolCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("池内连接保持只读会话：SELECT 可用，写入被拒")
    void pooledConnection_staysReadOnly() throws Exception {
        try (Connection connection = factory.open(target);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);

            assertThatThrownBy(() -> statement.execute(
                    "CREATE TABLE pool_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                            + " (id BIGINT PRIMARY KEY)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("连续 open/close 后池计数稳定，不泄漏池")
    void repeatedOpenClose_keepsSinglePool() throws Exception {
        for (int i = 0; i < 5; i++) {
            try (Connection connection = factory.open(target)) {
                assertThat(connection.isValid(3)).isTrue();
            }
        }
        assertThat(factory.cachedPoolCount()).isEqualTo(1);
    }
}
