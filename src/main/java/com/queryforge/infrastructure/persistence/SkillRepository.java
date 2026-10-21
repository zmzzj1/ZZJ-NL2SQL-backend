package com.queryforge.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import com.queryforge.domain.skill.SkillDefinition;
import com.queryforge.domain.skill.SkillVersion;

/** Skill 定义与版本的持久化。 */
@Repository
public class SkillRepository {

    private static final String DEF_COLUMNS = """
            id, user_id, name, slug, description, status, draft_revision, draft_content,
            published_version_id, created_at, updated_at
            """;

    private static final String VER_COLUMNS = """
            id, skill_id, version_no, content_snapshot, content_hash, published_by, published_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public SkillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long insertDefinition(Long userId, String name, String slug, String description, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO skill_definitions (user_id, name, slug, description, status, draft_revision, draft_content)
                    VALUES (?, ?, ?, ?, 'DRAFT', 0, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, name);
            statement.setString(3, slug);
            statement.setString(4, description);
            statement.setString(5, content);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Skill 创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<SkillDefinition> findOwned(Long id, Long userId) {
        List<SkillDefinition> rows = jdbcTemplate.query(
                "SELECT " + DEF_COLUMNS + " FROM skill_definitions WHERE id = ? AND user_id = ?",
                (rs, rowNum) -> mapDefinition(rs), id, userId);
        return rows.stream().findFirst();
    }

    public List<SkillDefinition> listOwned(Long userId, String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT " + DEF_COLUMNS + " FROM skill_definitions WHERE user_id = ? ORDER BY updated_at DESC, id DESC",
                    (rs, rowNum) -> mapDefinition(rs), userId);
        }
        return jdbcTemplate.query(
                "SELECT " + DEF_COLUMNS + " FROM skill_definitions WHERE user_id = ? AND status = ? ORDER BY updated_at DESC, id DESC",
                (rs, rowNum) -> mapDefinition(rs), userId, status);
    }

    public int updateDraft(Long id, Long userId, String name, String slug, String description,
                           String content, int expectedRevision) {
        return jdbcTemplate.update("""
                        UPDATE skill_definitions
                        SET name = ?, slug = ?, description = ?, draft_content = ?,
                            draft_revision = draft_revision + 1, updated_at = ?
                        WHERE id = ? AND user_id = ? AND draft_revision = ?
                        """,
                name, slug, description, content, LocalDateTime.now(), id, userId, expectedRevision);
    }

    public int disable(Long id, Long userId) {
        return jdbcTemplate.update("""
                        UPDATE skill_definitions SET status = 'DISABLED', updated_at = ?
                        WHERE id = ? AND user_id = ? AND status <> 'DISABLED'
                        """,
                LocalDateTime.now(), id, userId);
    }

    public int setPublishedVersion(Long id, Long userId, Long versionId, int expectedRevision) {
        return jdbcTemplate.update("""
                        UPDATE skill_definitions
                        SET published_version_id = ?, status = 'PUBLISHED',
                            draft_revision = draft_revision + 1, updated_at = ?
                        WHERE id = ? AND user_id = ? AND draft_revision = ?
                        """,
                versionId, LocalDateTime.now(), id, userId, expectedRevision);
    }

    public Long insertVersion(Long skillId, int versionNo, String contentSnapshot, String contentHash, Long publishedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO skill_versions (skill_id, version_no, content_snapshot, content_hash, published_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, skillId);
            statement.setInt(2, versionNo);
            statement.setString(3, contentSnapshot);
            statement.setString(4, contentHash);
            statement.setLong(5, publishedBy);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Skill 版本创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<SkillVersion> findVersion(Long versionId) {
        List<SkillVersion> rows = jdbcTemplate.query(
                "SELECT " + VER_COLUMNS + " FROM skill_versions WHERE id = ?",
                (rs, rowNum) -> mapVersion(rs), versionId);
        return rows.stream().findFirst();
    }

    public List<SkillVersion> listVersions(Long skillId) {
        return jdbcTemplate.query(
                "SELECT " + VER_COLUMNS + " FROM skill_versions WHERE skill_id = ? ORDER BY version_no DESC",
                (rs, rowNum) -> mapVersion(rs), skillId);
    }

    public int maxVersionNo(Long skillId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT COALESCE(MAX(version_no), 0) FROM skill_versions WHERE skill_id = ?",
                (rs, rowNum) -> rs.getInt(1), skillId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private static SkillDefinition mapDefinition(java.sql.ResultSet rs) throws java.sql.SQLException {
        long publishedVersionId = rs.getLong("published_version_id");
        Long nullablePublished = rs.wasNull() ? null : publishedVersionId;
        return new SkillDefinition(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"), rs.getString("slug"),
                rs.getString("description"), rs.getString("status"), rs.getInt("draft_revision"),
                rs.getString("draft_content"), nullablePublished,
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static SkillVersion mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SkillVersion(
                rs.getLong("id"), rs.getLong("skill_id"), rs.getInt("version_no"),
                rs.getString("content_snapshot"), rs.getString("content_hash"),
                rs.getLong("published_by"), rs.getTimestamp("published_at").toLocalDateTime());
    }
}
