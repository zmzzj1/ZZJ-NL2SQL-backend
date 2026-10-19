package com.queryforge.knowledge;

import com.queryforge.knowledge.KnowledgeEntry;
import com.queryforge.knowledge.KnowledgeType;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 业务术语与审核 SQL 示例的持久化；读写与删除全部按 user_id 校验。
 */
@Repository
public class KnowledgeRepository {

    private static final String COLUMNS = """
            id, user_id, connection_id, source_type, title, content, status, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(Long userId, Long connectionId, KnowledgeType sourceType, String title, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO knowledge_entries (user_id, connection_id, source_type, title, content)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            setNullableLong(statement, 2, connectionId);
            statement.setString(3, sourceType.name());
            statement.setString(4, title);
            statement.setString(5, content);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("知识条目创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<KnowledgeEntry> findOwned(Long id, Long userId) {
        List<KnowledgeEntry> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM knowledge_entries WHERE id = ? AND user_id = ?",
                (resultSet, rowNum) -> map(resultSet),
                id,
                userId);
        return rows.stream().findFirst();
    }

    public List<KnowledgeEntry> list(Long userId, KnowledgeType sourceType) {
        if (sourceType == null) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM knowledge_entries WHERE user_id = ? AND status = 'ACTIVE' ORDER BY id",
                    (resultSet, rowNum) -> map(resultSet),
                    userId);
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM knowledge_entries"
                        + " WHERE user_id = ? AND source_type = ? AND status = 'ACTIVE' ORDER BY id",
                (resultSet, rowNum) -> map(resultSet),
                userId,
                sourceType.name());
    }

    /** 召回候选：本人且作用域匹配（用户级或当前连接）。 */
    public List<KnowledgeEntry> searchCandidates(Long userId, Long connectionId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM knowledge_entries"
                        + " WHERE user_id = ? AND status = 'ACTIVE' AND (connection_id IS NULL OR connection_id = ?)",
                (resultSet, rowNum) -> map(resultSet),
                userId,
                connectionId);
    }

    /**
     * 召回候选（Workspace 感知）：本人知识 + 空间内已授权共享的知识。
     * 数据库级 JOIN resource_grants 过滤，确保跨空间/未授权知识不进入候选（不先取全库再隐藏）。
     * 本人知识的连接作用域仍匹配（connection_id IS NULL 或等于当前连接）；
     * 空间共享知识视为空间级知识，不受检索者 connection 作用域限制（由 ResourceGrant 授权决定可见性）。
     */
    public List<KnowledgeEntry> searchCandidatesInWorkspace(Long userId, Long connectionId, Long workspaceId) {
        String sql = "SELECT k.id, k.user_id, k.connection_id, k.source_type, k.title, k.content,"
                + " k.status, k.version, k.created_at, k.updated_at"
                + " FROM knowledge_entries k"
                + " WHERE k.status = 'ACTIVE'"
                + " AND ("
                + "   (k.user_id = ? AND (k.connection_id IS NULL OR k.connection_id = ?))"
                + "   OR EXISTS (SELECT 1 FROM resource_grants g"
                + "              WHERE g.resource_type = 'KNOWLEDGE' AND g.resource_id = k.id"
                + "                AND g.workspace_id = ? AND g.status = 'ACTIVE')"
                + " )";
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> map(resultSet), userId, connectionId, workspaceId);
    }

    public int update(Long id, Long userId, Long connectionId, String title, String content, int newVersion) {
        return jdbcTemplate.update("""
                        UPDATE knowledge_entries
                        SET connection_id = ?, title = ?, content = ?, version = ?, updated_at = ?
                        WHERE id = ? AND user_id = ?
                        """,
                connectionId, title, content, newVersion, LocalDateTime.now(), id, userId);
    }

    public int delete(Long id, Long userId) {
        return jdbcTemplate.update(
                "DELETE FROM knowledge_entries WHERE id = ? AND user_id = ?", id, userId);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static KnowledgeEntry map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        long connectionId = resultSet.getLong("connection_id");
        Long nullableConnectionId = resultSet.wasNull() ? null : connectionId;
        return new KnowledgeEntry(
                resultSet.getLong("id"),
                resultSet.getLong("user_id"),
                nullableConnectionId,
                KnowledgeType.valueOf(resultSet.getString("source_type")),
                resultSet.getString("title"),
                resultSet.getString("content"),
                resultSet.getString("status"),
                resultSet.getInt("version"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getTimestamp("updated_at").toLocalDateTime());
    }
}
