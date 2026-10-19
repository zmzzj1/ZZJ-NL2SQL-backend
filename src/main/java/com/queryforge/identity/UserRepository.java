package com.queryforge.identity;

import com.queryforge.identity.AppUserPrincipal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 用户表只存在于可写管理库，业务查询库没有用户数据。
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AppUserPrincipal> findByEmail(String email) {
        List<AppUserPrincipal> users = jdbcTemplate.query(
                "SELECT id, email, display_name, password_hash, role FROM users WHERE email = ?",
                (resultSet, rowNum) -> new AppUserPrincipal(
                        resultSet.getLong("id"),
                        resultSet.getString("email"),
                        resultSet.getString("display_name"),
                        resultSet.getString("password_hash"),
                        resultSet.getString("role")),
                email);
        return users.stream().findFirst();
    }

    public boolean existsByEmail(String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    public Long insert(String email, String displayName, String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO users (email, display_name, password_hash) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, email);
                statement.setString(2, displayName);
                statement.setString(3, passwordHash);
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateEmailException(email, ex);
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("用户创建后未返回主键");
        }
        return key.longValue();
    }

    /** 并发注册时唯一索引兜底，由 Service 转换为统一错误码。 */
    public static final class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String email, Throwable cause) {
            super("邮箱已注册：" + email, cause);
        }
    }
}
