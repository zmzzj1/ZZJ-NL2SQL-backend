package com.queryforge.infrastructure.persistence;

import com.queryforge.domain.workspace.WorkspaceEntities.Membership;
import com.queryforge.domain.workspace.WorkspaceEntities.ResourceGrant;
import com.queryforge.domain.workspace.WorkspaceEntities.Workspace;
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
 * Workspace、成员、资源授权与审计的持久化。
 * 所有查询带服务端身份过滤；成员/授权变更写审计。
 */
@Repository
public class WorkspaceRepository {

    private static final String WS_COLUMNS = "id, owner_id, name, description, status, created_at, updated_at";
    private static final String MEMBER_COLUMNS = """
            id, workspace_id, user_id, role, status, granted_by, granted_at, revoked_at
            """;
    private static final String GRANT_COLUMNS = """
            id, resource_type, resource_id, resource_owner, workspace_id, scope, status,
            granted_by, granted_at, revoked_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- Workspace ----

    public Long insertWorkspace(Long ownerId, String name, String description) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO workspaces (owner_id, name, description, status) VALUES (?, ?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, ownerId);
            statement.setString(2, name);
            statement.setString(3, description);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Workspace 创建后未返回主键");
        }
        return key.longValue();
    }

    public Optional<Workspace> findById(Long id) {
        List<Workspace> rows = jdbcTemplate.query(
                "SELECT " + WS_COLUMNS + " FROM workspaces WHERE id = ? AND status = 'ACTIVE'",
                (rs, rowNum) -> mapWorkspace(rs), id);
        return rows.stream().findFirst();
    }

    public int updateSettings(Long id, String name, String description) {
        return jdbcTemplate.update("""
                        UPDATE workspaces SET name = ?, description = ?, updated_at = ? WHERE id = ?
                        """,
                name, description, LocalDateTime.now(), id);
    }

    public int disable(Long id, Long ownerId) {
        return jdbcTemplate.update("""
                        UPDATE workspaces SET status = 'DISABLED', updated_at = ? WHERE id = ? AND owner_id = ?
                        """,
                LocalDateTime.now(), id, ownerId);
    }

    public int transferOwnership(Long id, Long fromOwnerId, Long toUserId) {
        return jdbcTemplate.update("""
                        UPDATE workspaces SET owner_id = ?, updated_at = ? WHERE id = ? AND owner_id = ?
                        """,
                toUserId, LocalDateTime.now(), id, fromOwnerId);
    }

    // ---- Membership ----

    public void insertMember(Long workspaceId, Long userId, String role, Long grantedBy) {
        jdbcTemplate.update("""
                        INSERT INTO workspace_members (workspace_id, user_id, role, status, granted_by)
                        VALUES (?, ?, ?, 'ACTIVE', ?)
                        ON DUPLICATE KEY UPDATE role = VALUES(role), status = 'ACTIVE',
                            granted_by = VALUES(granted_by), granted_at = CURRENT_TIMESTAMP(3), revoked_at = NULL
                        """,
                workspaceId, userId, role, grantedBy);
    }

    public Optional<Membership> findMember(Long workspaceId, Long userId) {
        List<Membership> rows = jdbcTemplate.query(
                "SELECT " + MEMBER_COLUMNS + " FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                (rs, rowNum) -> mapMember(rs), workspaceId, userId);
        return rows.stream().findFirst();
    }

    public List<Membership> listMembers(Long workspaceId) {
        return jdbcTemplate.query(
                "SELECT " + MEMBER_COLUMNS + " FROM workspace_members WHERE workspace_id = ? AND status = 'ACTIVE' ORDER BY id",
                (rs, rowNum) -> mapMember(rs), workspaceId);
    }

    public List<Workspace> listByMember(Long userId) {
        return jdbcTemplate.query("""
                        SELECT w.id, w.owner_id, w.name, w.description, w.status, w.created_at, w.updated_at
                        FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
                        WHERE m.user_id = ? AND m.status = 'ACTIVE' AND w.status = 'ACTIVE'
                        ORDER BY w.id
                        """,
                (rs, rowNum) -> mapWorkspace(rs), userId);
    }

    public int revokeMember(Long workspaceId, Long userId) {
        return jdbcTemplate.update("""
                        UPDATE workspace_members SET status = 'REVOKED', revoked_at = ? WHERE workspace_id = ? AND user_id = ? AND status = 'ACTIVE'
                        """,
                LocalDateTime.now(), workspaceId, userId);
    }

    public int updateMemberRole(Long workspaceId, Long userId, String role) {
        return jdbcTemplate.update("""
                        UPDATE workspace_members SET role = ? WHERE workspace_id = ? AND user_id = ? AND status = 'ACTIVE'
                        """,
                role, workspaceId, userId);
    }

    // ---- ResourceGrant ----

    public void insertGrant(String resourceType, Long resourceId, Long resourceOwner, Long workspaceId,
                            String scope, Long grantedBy) {
        jdbcTemplate.update("""
                        INSERT INTO resource_grants
                            (resource_type, resource_id, resource_owner, workspace_id, scope, status, granted_by)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                        ON DUPLICATE KEY UPDATE scope = VALUES(scope), status = 'ACTIVE',
                            resource_owner = VALUES(resource_owner), granted_by = VALUES(granted_by),
                            granted_at = CURRENT_TIMESTAMP(3), revoked_at = NULL
                        """,
                resourceType, resourceId, resourceOwner, workspaceId, scope, grantedBy);
    }

    public Optional<ResourceGrant> findGrant(String resourceType, Long resourceId, Long workspaceId) {
        List<ResourceGrant> rows = jdbcTemplate.query(
                "SELECT " + GRANT_COLUMNS + " FROM resource_grants"
                        + " WHERE resource_type = ? AND resource_id = ? AND workspace_id = ? AND status = 'ACTIVE'",
                (rs, rowNum) -> mapGrant(rs), resourceType, resourceId, workspaceId);
        return rows.stream().findFirst();
    }

    public List<ResourceGrant> listGrants(Long workspaceId, String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT " + GRANT_COLUMNS + " FROM resource_grants WHERE workspace_id = ? AND status = 'ACTIVE' ORDER BY id",
                    (rs, rowNum) -> mapGrant(rs), workspaceId);
        }
        return jdbcTemplate.query(
                "SELECT " + GRANT_COLUMNS + " FROM resource_grants"
                        + " WHERE workspace_id = ? AND resource_type = ? AND status = 'ACTIVE' ORDER BY id",
                (rs, rowNum) -> mapGrant(rs), workspaceId, resourceType);
    }

    public int revokeGrant(String resourceType, Long resourceId, Long workspaceId) {
        return jdbcTemplate.update("""
                        UPDATE resource_grants SET status = 'REVOKED', revoked_at = ?
                        WHERE resource_type = ? AND resource_id = ? AND workspace_id = ? AND status = 'ACTIVE'
                        """,
                LocalDateTime.now(), resourceType, resourceId, workspaceId);
    }

    // ---- Audit ----

    public void audit(Long workspaceId, Long actorId, String action, String objectType,
                      Long objectId, String result, String detail) {
        jdbcTemplate.update("""
                        INSERT INTO workspace_audit (workspace_id, actor_id, action, object_type, object_id, result, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                workspaceId, actorId, action, objectType, objectId, result, detail);
    }

    // ---- mapping ----

    private static Workspace mapWorkspace(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Workspace(
                rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private static Membership mapMember(java.sql.ResultSet rs) throws java.sql.SQLException {
        var revokedAt = rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toLocalDateTime();
        return new Membership(
                rs.getLong("id"), rs.getLong("workspace_id"), rs.getLong("user_id"), rs.getString("role"),
                rs.getString("status"), rs.getLong("granted_by"),
                rs.getTimestamp("granted_at").toLocalDateTime(), revokedAt);
    }

    private static ResourceGrant mapGrant(java.sql.ResultSet rs) throws java.sql.SQLException {
        var revokedAt = rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toLocalDateTime();
        return new ResourceGrant(
                rs.getLong("id"), rs.getString("resource_type"), rs.getLong("resource_id"),
                rs.getLong("resource_owner"), rs.getLong("workspace_id"), rs.getString("scope"),
                rs.getString("status"), rs.getLong("granted_by"),
                rs.getTimestamp("granted_at").toLocalDateTime(), revokedAt);
    }

    static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
