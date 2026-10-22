package com.queryforge.trigger.workspace;

import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.domain.workspace.WorkspaceEntities.GrantScope;
import com.queryforge.domain.workspace.WorkspaceEntities.Membership;
import com.queryforge.domain.workspace.WorkspaceEntities.ResourceGrant;
import com.queryforge.domain.workspace.WorkspaceEntities.ResourceType;
import com.queryforge.domain.workspace.WorkspaceEntities.Role;
import com.queryforge.domain.workspace.WorkspaceEntities.Workspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.queryforge.application.workspace.WorkspaceService;

/**
 * P8 Workspace 资源：空间、成员、角色与资源授权。
 * 身份只来自认证上下文；成员/授权变更均落审计。
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    public List<WorkspaceResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.list(principal.id()).stream().map(WorkspaceResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateWorkspaceRequest request) {
        return WorkspaceResponse.from(service.create(principal.id(), request.name(), request.description()));
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId) {
        return WorkspaceResponse.from(service.detail(principal.id(), workspaceId));
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId,
            @Valid @RequestBody CreateWorkspaceRequest request) {
        return WorkspaceResponse.from(service.updateSettings(principal.id(), workspaceId,
                request.name(), request.description()));
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId) {
        service.disable(principal.id(), workspaceId);
    }

    @PostMapping("/{workspaceId}/transfer")
    public void transfer(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId,
            @Valid @RequestBody TransferRequest request) {
        service.transferOwnership(principal.id(), workspaceId, request.newOwnerId());
    }

    // ---- 成员 ----

    @GetMapping("/{workspaceId}/members")
    public List<MemberResponse> listMembers(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId) {
        return service.listMembers(principal.id(), workspaceId).stream().map(MemberResponse::from).toList();
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse addMember(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId,
            @Valid @RequestBody AddMemberRequest request) {
        Membership membership = service.addMember(principal.id(), workspaceId, request.userId(),
                request.role() == null ? Role.VIEWER : request.role());
        return MemberResponse.from(membership);
    }

    @PatchMapping("/{workspaceId}/members/{memberUserId}")
    public MemberResponse updateMemberRole(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId,
            @PathVariable Long memberUserId,
            @Valid @RequestBody RoleRequest request) {
        service.updateMemberRole(principal.id(), workspaceId, memberUserId, request.role());
        return MemberResponse.from(service.requireActiveMember(workspaceId, memberUserId));
    }

    @DeleteMapping("/{workspaceId}/members/{memberUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId,
            @PathVariable Long memberUserId) {
        service.removeMember(principal.id(), workspaceId, memberUserId);
    }

    // ---- 资源授权 ----

    @GetMapping("/{workspaceId}/resources")
    public List<GrantResponse> listGrants(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId,
            @RequestParam(required = false) String resourceType) {
        return service.listGrants(principal.id(), workspaceId, resourceType).stream()
                .map(GrantResponse::from).toList();
    }

    @PostMapping("/{workspaceId}/resources")
    @ResponseStatus(HttpStatus.CREATED)
    public GrantResponse grant(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId,
            @Valid @RequestBody GrantRequest request) {
        ResourceGrant grant = service.grantResource(principal.id(), workspaceId,
                request.resourceType(), request.resourceId(), request.scope() == null ? GrantScope.READ : request.scope());
        return GrantResponse.from(grant);
    }

    @DeleteMapping("/{workspaceId}/resources/{resourceType}/{resourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long workspaceId,
            @PathVariable ResourceType resourceType,
            @PathVariable Long resourceId) {
        service.revokeResource(principal.id(), workspaceId, resourceType, resourceId);
    }

    // ---- DTO ----

    public record CreateWorkspaceRequest(
            @NotBlank String name,
            String description
    ) {
    }

    public record TransferRequest(@NotNull Long newOwnerId) {
    }

    public record AddMemberRequest(@NotNull Long userId, Role role) {
    }

    public record RoleRequest(@NotNull Role role) {
    }

    public record GrantRequest(
            @NotNull ResourceType resourceType,
            @NotNull Long resourceId,
            GrantScope scope
    ) {
    }

    public record WorkspaceResponse(
            Long id, Long ownerId, String name, String description, String status,
            String createdAt, String updatedAt
    ) {
        static WorkspaceResponse from(Workspace workspace) {
            return new WorkspaceResponse(workspace.id(), workspace.ownerId(), workspace.name(),
                    workspace.description(), workspace.status(), workspace.createdAt().toString(),
                    workspace.updatedAt().toString());
        }
    }

    public record MemberResponse(
            Long id, Long workspaceId, Long userId, String role, String status,
            Long grantedBy, String grantedAt
    ) {
        static MemberResponse from(Membership membership) {
            return new MemberResponse(membership.id(), membership.workspaceId(), membership.userId(),
                    membership.role(), membership.status(), membership.grantedBy(),
                    membership.grantedAt() == null ? null : membership.grantedAt().toString());
        }
    }

    public record GrantResponse(
            Long id, String resourceType, Long resourceId, Long resourceOwner, Long workspaceId,
            String scope, String status, Long grantedBy, String grantedAt
    ) {
        static GrantResponse from(ResourceGrant grant) {
            return new GrantResponse(grant.id(), grant.resourceType(), grant.resourceId(),
                    grant.resourceOwner(), grant.workspaceId(), grant.scope(), grant.status(),
                    grant.grantedBy(), grant.grantedAt() == null ? null : grant.grantedAt().toString());
        }
    }
}
