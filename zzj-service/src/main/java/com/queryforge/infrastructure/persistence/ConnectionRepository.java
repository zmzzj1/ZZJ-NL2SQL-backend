package com.queryforge.infrastructure.persistence;

import com.queryforge.domain.datasource.ConnectionConfig;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 连接配置只存管理库；所有读写都带 user_id 条件，防止越权。
 */
@Repository
public class ConnectionRepository {

    private static final String COLUMNS = """
            id, user_id, name, engine, host, port, database_name, username,
            password_ciphertext, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public ConnectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ConnectionConfig> findByUser(Long userId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM connections WHERE user_id = ? ORDER BY id",
                (resultSet, rowNum) -> map(resultSet),
                userId);
    }

    public Optional<ConnectionConfig> findOwned(Long id, Long userId) {
        List<ConnectionConfig> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM connections WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> map(resultSet),
                id,
                userId);
        return rows.stream().findFirst();
    }

    public Long insert(ConnectionConfig config) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO connections
                        (user_id, name, engine, host, port, database_name, username, password_ciphertext)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, config.userId());
            statement.setString(2, config.name());
            statement.setString(3, config.engine());
            statement.setString(4, config.host());
            statement.setInt(5, config.port());
            statement.setString(6, config.database());
            statement.setString(7, config.username());
            statement.setString(8, config.passwordCiphertext());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("连接创建后未返回主键");
        }
        return key.longValue();
    }

    public int update(Long id, Long userId, String name, String host, int port, String database,
                      String username, String passwordCiphertext) {
        return jdbcTemplate.update("""
                        UPDATE connections
                        SET name = ?, host = ?, port = ?, database_name = ?, username = ?,
                            password_ciphertext = ?, updated_at = ?
                        WHERE id = ? AND user_id = ?
                        """,
                name, host, port, database, username, passwordCiphertext, LocalDateTime.now(), id, userId);
    }

    public int delete(Long id, Long userId) {
        return jdbcTemplate.update("DELETE FROM connections WHERE id = ? AND user_id = ?", id, userId);
    }

    private static ConnectionConfig map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ConnectionConfig(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                resultSet.getString("name"),
                resultSet.getString("engine"),
                resultSet.getString("host"),
                resultSet.getInt("port"),
                resultSet.getString("database_name"),
                resultSet.getString("username"),
                resultSet.getString("password_ciphertext"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }
}
