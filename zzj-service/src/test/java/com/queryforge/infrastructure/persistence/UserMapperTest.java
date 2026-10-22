package com.queryforge.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.io.FileInputStream;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Properties;
import java.util.UUID;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.queryforge.domain.identity.UserEntity;

/**
 * P12 S1：MyBatis-Plus 管理实体（users）的真实 MySQL 验证（P1202/P1203）。
 *
 * <p>用本地管理库 {@code sql_platform} 的真实连接验证 BaseMapper CRUD、Wrapper 条件查询、
 * 唯一索引冲突与两用户归属隔离；无本地凭据时跳过，不冒充成功。测试数据用专属邮箱前缀，
 * 结束后清理，不影响既有 128 个真实用户。
 */
class UserMapperTest {

    private static final String EMAIL_PREFIX = "mp-test-";

    private HikariDataSource dataSource;
    private SqlSessionFactory sessionFactory;

    @BeforeEach
    void setUp() {
        Properties local = loadProps("config/local.properties", "../config/local.properties");
        Assumptions.assumeTrue(local != null, "无 backend/config/local.properties，跳过");

        String url = firstNonBlank(
                System.getenv("MANAGEMENT_DB_URL"),
                local.getProperty("app.management.url"),
                "jdbc:mysql://127.0.0.1:3306/sql_platform"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        String username = firstNonBlank(
                System.getenv("MANAGEMENT_DB_USERNAME"), local.getProperty("app.management.username"));
        String password = firstNonBlank(
                System.getenv("MANAGEMENT_DB_PASSWORD"), local.getProperty("app.management.password"));
        Assumptions.assumeTrue(username != null && password != null, "缺少管理库账号，跳过");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        try {
            dataSource = new HikariDataSource(config);
            dataSource.getConnection().close();
        } catch (Exception ex) {
            dataSource.close();
            Assumptions.abort("管理库不可达，跳过 MyBatis-Plus 集成验证");
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(UserMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() {
        if (sessionFactory != null && dataSource != null) {
            try (SqlSession session = sessionFactory.openSession(true)) {
                UserMapper mapper = session.getMapper(UserMapper.class);
                mapper.delete(new LambdaQueryWrapper<UserEntity>()
                        .like(UserEntity::getEmail, EMAIL_PREFIX + "%"));
            } catch (RuntimeException ignored) {
                // 清理失败不影响断言结论
            }
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private UserMapper mapper(SqlSession session) {
        return session.getMapper(UserMapper.class);
    }

    private String testEmail() {
        return EMAIL_PREFIX + UUID.randomUUID().toString().substring(0, 8) + "@local.test";
    }

    private UserEntity newUser(String email) {
        UserEntity entity = new UserEntity();
        entity.setEmail(email);
        entity.setDisplayName("映射测试");
        entity.setPasswordHash("$2a$10$notarealhashnotarealhashnotarealhashnotarealhashno");
        return entity;
    }

    @Test
    @DisplayName("insert + selectById + selectOne 完成 CRUD 往返，role 走数据库默认值")
    void insertThenReadRoundTrips() {
        String email = testEmail();
        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper mapper = mapper(session);
            UserEntity entity = newUser(email);
            mapper.insert(entity);
            assertThat(entity.getId()).isNotNull();

            UserEntity byId = mapper.selectById(entity.getId());
            assertThat(byId.getEmail()).isEqualTo(email);
            assertThat(byId.getDisplayName()).isEqualTo("映射测试");
            assertThat(byId.getRole()).isEqualTo("USER");

            UserEntity byEmail = mapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getEmail, email).last("LIMIT 1"));
            assertThat(byEmail.getId()).isEqualTo(entity.getId());
            assertThat(mapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getEmail, email))).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("updateById 条件更新生效，Wrapper 可按 id+email 精确命中")
    void updateByIdReflectedInQuery() {
        String email = testEmail();
        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper mapper = mapper(session);
            UserEntity entity = newUser(email);
            mapper.insert(entity);

            entity.setDisplayName("改名后");
            assertThat(mapper.updateById(entity)).isEqualTo(1);

            UserEntity reloaded = mapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getId, entity.getId())
                    .eq(UserEntity::getEmail, email));
            assertThat(reloaded.getDisplayName()).isEqualTo("改名后");
        }
    }

    @Test
    @DisplayName("唯一索引冲突沿异常链可识别（并发注册兜底语义）")
    void duplicateEmailIsDetectable() {
        String email = testEmail();
        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper mapper = mapper(session);
            mapper.insert(newUser(email));

            assertThatThrownBy(() -> mapper.insert(newUser(email)))
                    .satisfies(ex -> assertThat(hasDuplicateKeyCause(ex)).isTrue());
        }
    }

    @Test
    @DisplayName("两用户归属隔离：他人的邮箱查不到本人记录")
    void crossUserQueryIsolated() {
        String emailA = testEmail();
        String emailB = testEmail();
        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper mapper = mapper(session);
            UserEntity a = newUser(emailA);
            mapper.insert(a);
            mapper.insert(newUser(emailB));

            UserEntity leaked = mapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getId, a.getId())
                    .eq(UserEntity::getEmail, emailB));
            assertThat(leaked).isNull();
            assertThat(mapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                    .like(UserEntity::getEmail, EMAIL_PREFIX + "%"))).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("deleteById 后记录不可见")
    void deleteByIdRemovesRow() {
        String email = testEmail();
        try (SqlSession session = sessionFactory.openSession(true)) {
            UserMapper mapper = mapper(session);
            UserEntity entity = newUser(email);
            mapper.insert(entity);

            assertThat(mapper.deleteById(entity.getId())).isEqualTo(1);
            assertThat(mapper.selectById(entity.getId())).isNull();
            assertThat(mapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                    .eq(UserEntity::getEmail, email).last("LIMIT 1"))).isNull();
        }
    }

    private static boolean hasDuplicateKeyCause(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException
                    || current instanceof PersistenceException
                    || current instanceof org.springframework.dao.DuplicateKeyException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Properties loadProps(String... paths) {
        for (String path : paths) {
            File file = new File(path);
            if (file.exists()) {
                try (FileInputStream in = new FileInputStream(file)) {
                    Properties properties = new Properties();
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
