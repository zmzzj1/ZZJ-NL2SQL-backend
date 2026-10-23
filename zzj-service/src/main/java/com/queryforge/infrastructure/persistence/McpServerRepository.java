package com.queryforge.infrastructure.persistence;

import com.queryforge.domain.mcp.McpServerEntity;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 用户级 MCP 服务配置仓储。所有写入与读取都必须绑定已认证 {@code userId}，
 * 缺失或非本人统一以无泄露的“不存在”返回，绝不把任意用户传入的 {@code id}
 * 直接交给无条件 UPDATE / DELETE。
 */
@Repository
public class McpServerRepository {

    private final JdbcTemplate jdbcTemplate;

    public McpServerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insertOwned(Long userId, String name, String transport, String endpoint,
                            String status, String argsJson, String envMetaJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mcp_servers
                      (user_id, name, transport, endpoint, status, args, env_meta)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, name);
            statement.setString(3, transport);
            statement.setString(4, endpoint);
            statement.setString(5, status);
            setNullableString(statement, 6, argsJson);
            setNullableString(statement, 7, envMetaJson);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public Optional<McpServerEntity> findOwned(Long id, Long userId) {
        List<McpServerEntity> rows = jdbcTemplate.query(
                "SELECT id, user_id, name, transport, endpoint, status, args, env_meta, "
                        + "created_at, updated_at FROM mcp_servers WHERE id = ? AND user_id = ?",
                MAPPER, id, userId);
        return rows.stream().findFirst();
    }

    public List<McpServerEntity> listOwned(Long userId) {
        return jdbcTemplate.query(
                "SELECT id, user_id, name, transport, endpoint, status, args, env_meta, "
                        + "created_at, updated_at FROM mcp_servers WHERE user_id = ? ORDER BY id ASC",
                MAPPER, userId);
    }

    public int updateOwned(Long id, Long userId, String name, String transport, String endpoint,
                           String status, String argsJson, String envMetaJson) {
        return jdbcTemplate.update(
                "UPDATE mcp_servers SET name = ?, transport = ?, endpoint = ?, status = ?, "
                        + "args = ?, env_meta = ? WHERE id = ? AND user_id = ?",
                name, transport, endpoint, status, argsJson, envMetaJson, id, userId);
    }

    public int deleteOwned(Long id, Long userId) {
        return jdbcTemplate.update("DELETE FROM mcp_servers WHERE id = ? AND user_id = ?", id, userId);
    }

    /**
     * 启动恢复用：列出所有标记为启用（status='ACTIVE'）的配置。读取的是持久意图，
     * 不代表真实在线；调用方需对每个 server 重新建立连接以确认运行时事实。
     * 返回的实体带各自归属的 {@code userId}，仅由服务端恢复流程使用，绝不接受外部传入。
     */
    public List<McpServerEntity> listAllActive() {
        return jdbcTemplate.query(
                "SELECT id, user_id, name, transport, endpoint, status, args, env_meta, "
                        + "created_at, updated_at FROM mcp_servers WHERE status = 'ACTIVE' ORDER BY id ASC",
                MAPPER);
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.LONGVARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    private static final RowMapper<McpServerEntity> MAPPER = (rs, rowNum) -> new McpServerEntity(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("name"),
            rs.getString("transport"),
            rs.getString("endpoint"),
            rs.getString("status"),
            rs.getString("args"),
            rs.getString("env_meta"),
            toLocalDateTime(rs.getObject("created_at")),
            toLocalDateTime(rs.getObject("updated_at")));
}
