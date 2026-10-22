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
import com.queryforge.domain.studio.AgentDefinition;
import com.queryforge.domain.studio.AgentVersion;

/**
 * Agent 定义与版本的持久化。读取带 user_id 校验，更新带 expectedRevision 乐观锁。
 * 发布快照不可变：只插入 agent_versions，不更新。
 */
@Repository
public class StudioRepository {

    private static final String DEF_COLUMNS = """
            id, user_id, name, description, status, draft_revision, draft_content,
            published_version_id, created_at, updated_at
            """;

    private static final String VER_COLUMNS = """
            id, agent_id, version_no, content_snapshot, skill_version_refs, tool_ids,
            model_ref, content_hash, published_by, published_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public StudioRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- Agent 定义 ----

    public Long insertDefinition(Long userId, String name, String description, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_definitions (user_id, name, description, status, draft_revision, draft_content)
                    VALUES (?, ?, ?, 'DRAFT', 0, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, name);
            statement.setString(3, description);
            statement.setString(4, content);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Agent 创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<AgentDefinition> findOwned(Long id, Long userId) {
        List<AgentDefinition> rows = jdbcTemplate.query(
                "SELECT " + DEF_COLUMNS + " FROM agent_definitions WHERE id = ? AND user_id = ?",
                (rs, rowNum) -> mapDefinition(rs), id, userId);
        return rows.stream().findFirst();
    }

    public List<AgentDefinition> listOwned(Long userId, String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT " + DEF_COLUMNS + " FROM agent_definitions WHERE user_id = ? ORDER BY updated_at DESC, id DESC",
                    (rs, rowNum) -> mapDefinition(rs), userId);
        }
        return jdbcTemplate.query(
                "SELECT " + DEF_COLUMNS + " FROM agent_definitions WHERE user_id = ? AND status = ? ORDER BY updated_at DESC, id DESC",
                (rs, rowNum) -> mapDefinition(rs), userId, status);
    }

    /** 乐观锁更新草稿：只有 revision 匹配才生效，返回受影响行数（0 = 冲突）。 */
    public int updateDraft(Long id, Long userId, String name, String description, String content, int expectedRevision) {
        return jdbcTemplate.update("""
                        UPDATE agent_definitions
                        SET name = ?, description = ?, draft_content = ?, draft_revision = draft_revision + 1, updated_at = ?
                        WHERE id = ? AND user_id = ? AND draft_revision = ?
                        """,
                name, description, content, LocalDateTime.now(), id, userId, expectedRevision);
    }

    public int archive(Long id, Long userId) {
        return jdbcTemplate.update("""
                        UPDATE agent_definitions SET status = 'ARCHIVED', updated_at = ?
                        WHERE id = ? AND user_id = ? AND status <> 'ARCHIVED'
                        """,
                LocalDateTime.now(), id, userId);
    }

    /** 原子发布：校验 revision 并设置发布指针。返回受影响行数（0 = 冲突）。 */
    public int setPublishedVersion(Long id, Long userId, Long versionId, int expectedRevision) {
        return jdbcTemplate.update("""
                        UPDATE agent_definitions
                        SET published_version_id = ?, draft_revision = draft_revision + 1, updated_at = ?
                        WHERE id = ? AND user_id = ? AND draft_revision = ?
                        """,
                versionId, LocalDateTime.now(), id, userId, expectedRevision);
    }

    public int updatePublishedVersion(Long id, Long userId, Long versionId) {
        return jdbcTemplate.update("""
                        UPDATE agent_definitions SET published_version_id = ?, updated_at = ?
                        WHERE id = ? AND user_id = ?
                        """,
                versionId, LocalDateTime.now(), id, userId);
    }

    // ---- Agent 版本 ----

    public Long insertVersion(Long agentId, int versionNo, String contentSnapshot, String skillVersionRefs,
                              String toolIds, String modelRef, String contentHash, Long publishedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_versions
                        (agent_id, version_no, content_snapshot, skill_version_refs, tool_ids, model_ref, content_hash, published_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, agentId);
            statement.setInt(2, versionNo);
            statement.setString(3, contentSnapshot);
            statement.setString(4, skillVersionRefs);
            statement.setString(5, toolIds);
            statement.setString(6, modelRef);
            statement.setString(7, contentHash);
            statement.setLong(8, publishedBy);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Agent 版本创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<AgentVersion> findVersion(Long versionId) {
        List<AgentVersion> rows = jdbcTemplate.query(
                "SELECT " + VER_COLUMNS + " FROM agent_versions WHERE id = ?",
                (rs, rowNum) -> mapVersion(rs), versionId);
        return rows.stream().findFirst();
    }

    public List<AgentVersion> listVersions(Long agentId) {
        return jdbcTemplate.query(
                "SELECT " + VER_COLUMNS + " FROM agent_versions WHERE agent_id = ? ORDER BY version_no DESC",
                (rs, rowNum) -> mapVersion(rs), agentId);
    }

    public int maxVersionNo(Long agentId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT COALESCE(MAX(version_no), 0) FROM agent_versions WHERE agent_id = ?",
                (rs, rowNum) -> rs.getInt(1), agentId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    // ---- 审计 ----

    public void audit(Long userId, String objectType, Long objectId, String action,
                      Integer versionNo, String result, String detail) {
        jdbcTemplate.update("""
                        INSERT INTO config_audit (user_id, object_type, object_id, action, version_no, result, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                userId, objectType, objectId, action, versionNo, result, detail);
    }

    private static AgentDefinition mapDefinition(java.sql.ResultSet rs) throws java.sql.SQLException {
        long publishedVersionId = rs.getLong("published_version_id");
        Long nullablePublished = rs.wasNull() ? null : publishedVersionId;
        return new AgentDefinition(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), rs.getInt("draft_revision"), rs.getString("draft_content"),
                nullablePublished, rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static AgentVersion mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentVersion(
                rs.getLong("id"), rs.getLong("agent_id"), rs.getInt("version_no"),
                rs.getString("content_snapshot"), rs.getString("skill_version_refs"),
                rs.getString("tool_ids"), rs.getString("model_ref"), rs.getString("content_hash"),
                rs.getLong("published_by"), rs.getTimestamp("published_at").toLocalDateTime());
    }
}
