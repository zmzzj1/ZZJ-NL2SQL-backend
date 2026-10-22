package com.queryforge.application.workspace;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.domain.workspace.WorkspaceEntities.GrantScope;
import com.queryforge.domain.workspace.WorkspaceEntities.Membership;
import com.queryforge.domain.workspace.WorkspaceEntities.ResourceGrant;
import com.queryforge.domain.workspace.WorkspaceEntities.ResourceType;
import com.queryforge.domain.workspace.WorkspaceEntities.Role;
import com.queryforge.domain.workspace.WorkspaceEntities.Workspace;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.queryforge.infrastructure.persistence.WorkspaceRepository;

/**
 * Workspace 业务边界：空间、成员、角色权限矩阵、资源授权（ResourceGrant）与审计。
 *
 * 安全硬边界：
 *  - 用户身份来自认证上下文（userId），绝不接受请求体中的 ownerId/workspaceId 越权。
 *  - Workspace 角色（OWNER/ADMIN/EDITOR/VIEWER）只决定「知识/Agent/报告的查看与编辑」，
 *    不自动授权任何 SQL 执行、数据源或工具 —— 那些始终走 P4/P6 的归属与工具白名单。
 *  - 资源（Agent/Knowledge/Report/Connection）跨空间共享必须显式 ResourceGrant。
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository repository;

    public WorkspaceService(WorkspaceRepository repository) {
        this.repository = repository;
    }

    // ---- 空间 CRUD ----

    @Transactional
    public Workspace create(Long userId, String name, String description) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "空间名称非法或超长");
        }
        Long id = repository.insertWorkspace(userId, normalized,
                description == null ? null : description.trim());
        repository.insertMember(id, userId, Role.OWNER.name(), userId);
        repository.audit(id, userId, "MEMBER_ADD", "WORKSPACE", id, "SUCCESS", "owner 自动加入");
        return repository.findById(id).orElseThrow();
    }

    public List<Workspace> list(Long userId) {
        return repository.listByMember(userId);
    }

    public Workspace detail(Long userId, Long workspaceId) {
        Membership membership = requireActiveMember(workspaceId, userId);
        return repository.findById(workspaceId).orElseThrow(WorkspaceService::notFound);
    }

    @Transactional
    public Workspace updateSettings(Long userId, Long workspaceId, String name, String description) {
        requireRole(workspaceId, userId, Role.ADMIN);
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "空间名称非法或超长");
        }
        repository.updateSettings(workspaceId, normalized, description == null ? null : description.trim());
        repository.audit(workspaceId, userId, "SETTING", "WORKSPACE", workspaceId, "SUCCESS", null);
        return repository.findById(workspaceId).orElseThrow();
    }

    @Transactional
    public void disable(Long userId, Long workspaceId) {
        requireRole(workspaceId, userId, Role.OWNER);
        repository.disable(workspaceId, userId);
        repository.audit(workspaceId, userId, "SETTING", "WORKSPACE", workspaceId, "SUCCESS", "disable");
    }

    @Transactional
    public void transferOwnership(Long userId, Long workspaceId, Long newOwnerId) {
        requireRole(workspaceId, userId, Role.OWNER);
        Membership target = requireActiveMember(workspaceId, newOwnerId);
        repository.transferOwnership(workspaceId, userId, newOwnerId);
        repository.updateMemberRole(workspaceId, userId, Role.ADMIN.name());
        repository.updateMemberRole(workspaceId, newOwnerId, Role.OWNER.name());
        repository.audit(workspaceId, userId, "SETTING", "WORKSPACE", workspaceId, "SUCCESS",
                "transfer to user " + newOwnerId);
    }

    // ---- 成员 ----

    @Transactional
    public Membership addMember(Long userId, Long workspaceId, Long memberUserId, Role role) {
        requireRole(workspaceId, userId, Role.ADMIN);
        Role effective = role == null ? Role.VIEWER : role;
        if (effective == Role.OWNER) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                    "不能通过成员邀请授予 OWNER 角色，请使用转移所有权");
        }
        repository.insertMember(workspaceId, memberUserId, effective.name(), userId);
        repository.audit(workspaceId, userId, "MEMBER_ADD", "USER", memberUserId, "SUCCESS",
                "role=" + effective.name());
        return repository.findMember(workspaceId, memberUserId).orElseThrow();
    }

    public List<Membership> listMembers(Long userId, Long workspaceId) {
        requireActiveMember(workspaceId, userId);
        return repository.listMembers(workspaceId);
    }

    @Transactional
    public void removeMember(Long userId, Long workspaceId, Long memberUserId) {
        requireRole(workspaceId, userId, Role.ADMIN);
        if (memberUserId.equals(ownerOf(workspaceId))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "不能移除空间所有者");
        }
        repository.revokeMember(workspaceId, memberUserId);
        repository.audit(workspaceId, userId, "MEMBER_REVOKE", "USER", memberUserId, "SUCCESS", null);
    }

    @Transactional
    public void updateMemberRole(Long userId, Long workspaceId, Long memberUserId, Role role) {
        requireRole(workspaceId, userId, Role.ADMIN);
        if (role == Role.OWNER) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                    "不能通过角色更新授予 OWNER，请使用转移所有权");
        }
        requireActiveMember(workspaceId, memberUserId);
        repository.updateMemberRole(workspaceId, memberUserId, role.name());
        repository.audit(workspaceId, userId, "SETTING", "USER", memberUserId, "SUCCESS", "role=" + role.name());
    }

    // ---- 资源授权 ----

    @Transactional
    public ResourceGrant grantResource(Long userId, Long workspaceId, ResourceType resourceType,
                                       Long resourceId, GrantScope scope) {
        requireRole(workspaceId, userId, Role.EDITOR);
        // 资源 owner 必须是当前用户（只能共享自己的资源）
        GrantScope effective = scope == null ? GrantScope.READ : scope;
        repository.insertGrant(resourceType.name(), resourceId, userId, workspaceId,
                effective.name(), userId);
        repository.audit(workspaceId, userId, "GRANT", resourceType.name(), resourceId, "SUCCESS",
                "scope=" + effective.name());
        return repository.findGrant(resourceType.name(), resourceId, workspaceId).orElseThrow();
    }

    @Transactional
    public void revokeResource(Long userId, Long workspaceId, ResourceType resourceType, Long resourceId) {
        requireRole(workspaceId, userId, Role.EDITOR);
        repository.revokeGrant(resourceType.name(), resourceId, workspaceId);
        repository.audit(workspaceId, userId, "REVOKE", resourceType.name(), resourceId, "SUCCESS", null);
    }

    public List<ResourceGrant> listGrants(Long userId, Long workspaceId, String resourceType) {
        requireActiveMember(workspaceId, userId);
        return repository.listGrants(workspaceId, resourceType);
    }

    // ---- 判权（供 W2/W5 复用）----

    /**
     * 判断用户在指定空间是否为有效成员；返回成员关系，否则 404（不泄露空间存在性）。
     */
    public Membership requireActiveMember(Long workspaceId, Long userId) {
        Membership membership = repository.findMember(workspaceId, userId)
                .filter(m -> "ACTIVE".equals(m.status()))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.WORKSPACE_NOT_FOUND, HttpStatus.NOT_FOUND, "空间不存在或无权访问"));
        return membership;
    }

    /** 要求成员至少为指定角色。 */
    public Membership requireRole(Long workspaceId, Long userId, Role minimum) {
        Membership membership = requireActiveMember(workspaceId, userId);
        Role actual = Role.valueOf(membership.role());
        if (actual.rank() < minimum.rank()) {
            throw new ApiException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, "没有执行该操作的权限");
        }
        return membership;
    }

    /** 判断成员是否拥有「查看」权限（VIEWER 及以上）。 */
    public boolean canView(Membership membership) {
        return membership != null && "ACTIVE".equals(membership.status());
    }

    /** 判断成员是否拥有「编辑知识/Agent」权限（EDITOR 及以上）。 */
    public boolean canEdit(Membership membership) {
        return membership != null && "ACTIVE".equals(membership.status())
                && Role.valueOf(membership.role()).rank() >= Role.EDITOR.rank();
    }

    /** 判断成员是否拥有「管理成员」权限（ADMIN 及以上）。 */
    public boolean canManage(Membership membership) {
        return membership != null && "ACTIVE".equals(membership.status())
                && Role.valueOf(membership.role()).rank() >= Role.ADMIN.rank();
    }

    /**
     * 判断用户在空间内对某资源是否有有效授权（READ 及以上）。
     * 返回授权（含 scope），无则 null。资源 owner 自身天然可访问。
     */
    public ResourceGrant effectiveGrant(Long workspaceId, Long userId, ResourceType resourceType, Long resourceId) {
        ResourceGrant grant = repository.findGrant(resourceType.name(), resourceId, workspaceId).orElse(null);
        if (grant == null) {
            return null;
        }
        if (!"ACTIVE".equals(grant.status())) {
            return null;
        }
        return grant;
    }

    private Long ownerOf(Long workspaceId) {
        return repository.findById(workspaceId).orElseThrow(WorkspaceService::notFound).ownerId();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, HttpStatus.NOT_FOUND, "空间不存在或无权访问");
    }
}
