package com.queryforge.infrastructure.persistence;

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
import com.queryforge.domain.memory.LongTermMemory;

/**
 * 长期记忆持久化。读写按 owner + scope 权限过滤；工作空间记忆按成员资格访问。
 */
@Repository
public class MemoryRepository {

    private static final String COLUMNS = """
            id, owner_id, workspace_id, memory_type, scope, title, content, source_ref,
            confidence, status, expires_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public MemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insert(Long ownerId, Long workspaceId, String memoryType, String scope, String title,
                       String content, String sourceRef, String confidence, LocalDateTime expiresAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO long_term_memories
                        (owner_id, workspace_id, memory_type, scope, title, content, source_ref,
                         confidence, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, ownerId);
            setNullableLong(statement, 2, workspaceId);
            statement.setString(3, memoryType);
            statement.setString(4, scope);
            statement.setString(5, title);
            statement.setString(6, content);
            statement.setString(7, sourceRef);
            statement.setString(8, confidence);
            if (expiresAt == null) {
                statement.setNull(9, Types.TIMESTAMP);
            } else {
                statement.setObject(9, expiresAt);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("记忆创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<LongTermMemory> findOwned(Long id, Long ownerId) {
        List<LongTermMemory> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM long_term_memories WHERE id = ? AND owner_id = ?",
                (rs, rowNum) -> map(rs), id, ownerId);
        return rows.stream().findFirst();
    }

    /** 本人某作用域内可检索的记忆（个人 + 指定空间）。 */
    public List<LongTermMemory> searchCandidates(Long ownerId, Long workspaceId, String scope) {
        if ("WORKSPACE".equals(scope) && workspaceId != null) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM long_term_memories"
                            + " WHERE scope = 'WORKSPACE' AND workspace_id = ? AND status = 'ACTIVE'"
                            + " AND (expires_at IS NULL OR expires_at > ?)",
                    (rs, rowNum) -> map(rs), workspaceId, LocalDateTime.now());
        }
        // 个人记忆：owner 本人 + scope=PERSONAL
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM long_term_memories"
                        + " WHERE owner_id = ? AND scope = 'PERSONAL' AND status = 'ACTIVE'"
                        + " AND (expires_at IS NULL OR expires_at > ?)",
                (rs, rowNum) -> map(rs), ownerId, LocalDateTime.now());
    }

    public List<LongTermMemory> listOwned(Long ownerId, String memoryType) {
        if (memoryType == null || memoryType.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM long_term_memories WHERE owner_id = ? ORDER BY id DESC",
                    (rs, rowNum) -> map(rs), ownerId);
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM long_term_memories WHERE owner_id = ? AND memory_type = ? ORDER BY id DESC",
                (rs, rowNum) -> map(rs), ownerId, memoryType);
    }

    public int update(Long id, Long ownerId, String title, String content, String confidence) {
        return jdbcTemplate.update("""
                        UPDATE long_term_memories SET title = ?, content = ?, confidence = ?, updated_at = ?
                        WHERE id = ? AND owner_id = ?
                        """,
                title, content, confidence, LocalDateTime.now(), id, ownerId);
    }

    public int disable(Long id, Long ownerId) {
        return jdbcTemplate.update("""
                        UPDATE long_term_memories SET status = 'DISABLED', updated_at = ? WHERE id = ? AND owner_id = ?
                        """,
                LocalDateTime.now(), id, ownerId);
    }

    public int delete(Long id, Long ownerId) {
        return jdbcTemplate.update(
                "DELETE FROM long_term_memories WHERE id = ? AND owner_id = ?", id, ownerId);
    }

    /** 去重指纹：返回是否存在相同 fingerprint 的活跃记忆 id。 */
    public Optional<Long> findFingerprint(Long ownerId, Long workspaceId, String memoryType,
                                          String scope, String fingerprint) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT memory_id FROM memory_fingerprints WHERE owner_id = ? AND scope = ? AND memory_type = ?"
                        + " AND fingerprint = ? AND (workspace_id IS NULL OR workspace_id = ?)",
                (rs, rowNum) -> rs.getLong(1), ownerId, scope, memoryType, fingerprint, workspaceId);
        return rows.stream().findFirst();
    }

    public void insertFingerprint(Long ownerId, Long workspaceId, String memoryType, String scope,
                                  String fingerprint, Long memoryId) {
        jdbcTemplate.update("""
                        INSERT INTO memory_fingerprints (owner_id, workspace_id, memory_type, scope, fingerprint, memory_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                ownerId, workspaceId, memoryType, scope, fingerprint, memoryId);
    }

    private static LongTermMemory map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long workspaceId = rs.getLong("workspace_id");
        Long nullableWs = rs.wasNull() ? null : workspaceId;
        var expiresAt = rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toLocalDateTime();
        return new LongTermMemory(
                rs.getLong("id"), rs.getLong("owner_id"), nullableWs, rs.getString("memory_type"),
                rs.getString("scope"), rs.getString("title"), rs.getString("content"),
                rs.getString("source_ref"), rs.getString("confidence"), rs.getString("status"),
                expiresAt, rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
