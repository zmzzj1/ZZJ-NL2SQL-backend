package com.queryforge.workspace;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.workspace.WorkspaceEntities.Membership;
import com.queryforge.workspace.WorkspaceEntities.ResourceGrant;
import com.queryforge.workspace.WorkspaceEntities.ResourceType;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Workspace 访问策略：P8 的统一资源判权入口。
 *
 * 关键安全语义（对应 P8 指南 3.1/3.2）：
 *  - 用户身份 ≠ 成员 ≠ 资源使用权限；授权全部服务端决定。
 *  - 成员资格（Membership）决定「知识/Agent/报告」的查看/编辑，但绝不自动授权 SQL 执行或数据源。
 *  - 跨空间共享资源必须显式 ResourceGrant；获得 Agent 使用权不等于获得其引用的连接密码与执行权限。
 *  - 撤销后立即生效：判权每次实时查库，不缓存旧授权。
 */
@Service
public class WorkspaceAccessPolicy {

    private final WorkspaceService workspaceService;

    public WorkspaceAccessPolicy(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * 校验用户在空间内的成员资格，返回成员关系（含角色）。
     * 非成员抛出 WORKSPACE_NOT_FOUND，不泄露空间存在性。
     */
    public Membership requireMember(Long workspaceId, Long userId) {
        return workspaceService.requireActiveMember(workspaceId, userId);
    }

    /**
     * 判断用户在空间内是否有权「查看」某资源：
     *  1) 资源 owner 自身天然可访问；
     *  2) 存在 ACTIVE 的 ResourceGrant。
     * 注意：这只决定「能否查看元数据/已授权内容」，不决定 SQL 执行、连接密码或工具。
     */
    public boolean canViewResource(Long workspaceId, Long userId, ResourceType resourceType,
                                   Long resourceId, Long resourceOwner) {
        if (resourceOwner != null && resourceOwner.equals(userId)) {
            return true;
        }
        ResourceGrant grant = workspaceService.effectiveGrant(workspaceId, userId, resourceType, resourceId);
        return grant != null;
    }

    /**
     * 判断用户在空间内是否有权「编辑」某资源：必须存在 EDIT scope 的授权，且成员角色为 EDITOR 及以上。
     */
    public boolean canEditResource(Long workspaceId, Long userId, ResourceType resourceType,
                                   Long resourceId, Long resourceOwner) {
        if (resourceOwner != null && resourceOwner.equals(userId)) {
            return true;
        }
        Membership membership = workspaceService.requireActiveMember(workspaceId, userId);
        if (!workspaceService.canEdit(membership)) {
            return false;
        }
        ResourceGrant grant = workspaceService.effectiveGrant(workspaceId, userId, resourceType, resourceId);
        return grant != null && "EDIT".equals(grant.scope());
    }

    /**
     * 空间内资源可见性校验：无权则抛出（403，区分于 404 的「不存在」语义，
     * 因为资源确实存在但未授权）。用于 Agent 详情、报告详情、知识条目等。
     */
    public void requireView(Long workspaceId, Long userId, ResourceType resourceType,
                            Long resourceId, Long resourceOwner) {
        if (!canViewResource(workspaceId, userId, resourceType, resourceId, resourceOwner)) {
            throw new ApiException(ErrorCode.WORKSPACE_FORBIDDEN, HttpStatus.FORBIDDEN,
                    "资源未授权给当前空间");
        }
    }

    /**
     * 返回当前用户在给定空间内「可检索的知识 owner 集合」。
     * 语义：检索不能先取全库敏感 Top-K 再隐藏，而是在数据库级候选筛选阶段就限定
     * owner ∈ {本人} ∪ {已授权共享该资源的 owner}。
     *
     * 由于 ResourceGrant 按 resourceId 粒度，这里返回「本人」以及一个占位标记；
     * 具体候选 SQL 由 KnowledgeRepository 通过 grant 子查询 JOIN 过滤。
     */
    public boolean canAccessKnowledgeScope(Long workspaceId, Long userId) {
        // 成员即可访问空间知识（受 grant 过滤），非成员无权。
        workspaceService.requireActiveMember(workspaceId, userId);
        return true;
    }
}
