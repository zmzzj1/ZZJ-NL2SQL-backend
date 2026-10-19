package com.queryforge.workspace;

import java.time.LocalDateTime;

/**
 * Workspace 及其成员、资源授权、审计的持久化实体。
 * 权限一律服务端决定；成员关系独立于资源使用权限。
 */
public final class WorkspaceEntities {

    private WorkspaceEntities() {
    }

    public record Workspace(
            Long id, Long ownerId, String name, String description, String status,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record Membership(
            Long id, Long workspaceId, Long userId, String role, String status,
            Long grantedBy, LocalDateTime grantedAt, LocalDateTime revokedAt) {
    }

    public record ResourceGrant(
            Long id, String resourceType, Long resourceId, Long resourceOwner, Long workspaceId,
            String scope, String status, Long grantedBy, LocalDateTime grantedAt, LocalDateTime revokedAt) {
    }

    /** 成员角色（rank 越大权限越高）。 */
    public enum Role {
        VIEWER(0), EDITOR(1), ADMIN(2), OWNER(3);

        private final int rank;

        Role(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    /** 资源类型。 */
    public enum ResourceType {
        AGENT, KNOWLEDGE, REPORT, CONNECTION
    }

    /** 授权范围。 */
    public enum GrantScope {
        READ, EDIT
    }
}
