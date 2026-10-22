package com.queryforge.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.domain.workspace.WorkspaceEntities.Membership;
import com.queryforge.domain.workspace.WorkspaceEntities.Role;
import com.queryforge.domain.workspace.WorkspaceEntities.Workspace;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.queryforge.infrastructure.persistence.WorkspaceRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workspace 权限矩阵与成员")
class WorkspaceServiceTest {

    private static final long OWNER_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final long OUTSIDER_ID = 3L;
    private static final long WORKSPACE_ID = 100L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 0, 0);

    @Mock
    private WorkspaceRepository repository;

    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(repository);
    }

    @Test
    @DisplayName("创建空间后 owner 自动加入")
    void create_shouldAddOwnerAsMember() {
        when(repository.insertWorkspace(OWNER_ID, "运营分析", null)).thenReturn(WORKSPACE_ID);
        when(repository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));

        Workspace created = service.create(OWNER_ID, "运营分析", null);

        assertThat(created.id()).isEqualTo(WORKSPACE_ID);
        org.mockito.Mockito.verify(repository).insertMember(WORKSPACE_ID, OWNER_ID, "OWNER", OWNER_ID);
    }

    @Test
    @DisplayName("非成员访问空间返回 WORKSPACE_NOT_FOUND（不泄露存在性）")
    void detail_shouldHideSpaceFromOutsider() {
        when(repository.findMember(WORKSPACE_ID, OUTSIDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(OUTSIDER_ID, WORKSPACE_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    @Test
    @DisplayName("VIEWER 不能管理成员（FORBIDDEN）")
    void addMember_shouldRejectViewer() {
        when(repository.findMember(WORKSPACE_ID, MEMBER_ID))
                .thenReturn(Optional.of(member(MEMBER_ID, Role.VIEWER)));

        assertThatThrownBy(() -> service.addMember(MEMBER_ID, WORKSPACE_ID, OUTSIDER_ID, Role.VIEWER))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("EDITOR 不能管理成员但可编辑资源")
    void editor_canEditButNotManage() {
        Membership editor = member(MEMBER_ID, Role.EDITOR);
        assertThat(service.canEdit(editor)).isTrue();
        assertThat(service.canManage(editor)).isFalse();

        when(repository.findMember(WORKSPACE_ID, MEMBER_ID)).thenReturn(Optional.of(editor));
        assertThatThrownBy(() -> service.addMember(MEMBER_ID, WORKSPACE_ID, OUTSIDER_ID, Role.VIEWER))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("ADMIN 可管理成员但不可转移所有权")
    void admin_canManageButNotTransfer() {
        Membership admin = member(MEMBER_ID, Role.ADMIN);
        assertThat(service.canManage(admin)).isTrue();

        when(repository.findMember(WORKSPACE_ID, MEMBER_ID)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.transferOwnership(MEMBER_ID, WORKSPACE_ID, OUTSIDER_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("不能通过成员邀请授予 OWNER 角色")
    void addMember_shouldRejectOwnerRole() {
        when(repository.findMember(WORKSPACE_ID, OWNER_ID))
                .thenReturn(Optional.of(member(OWNER_ID, Role.OWNER)));

        assertThatThrownBy(() -> service.addMember(OWNER_ID, WORKSPACE_ID, OUTSIDER_ID, Role.OWNER))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("撤销成员后，该成员不再是有效成员")
    void revoke_shouldInvalidateMembership() {
        Membership revoked = new Membership(9L, WORKSPACE_ID, MEMBER_ID, "VIEWER", "REVOKED",
                OWNER_ID, NOW, NOW);
        when(repository.findMember(WORKSPACE_ID, MEMBER_ID)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.requireActiveMember(WORKSPACE_ID, MEMBER_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    private static Workspace workspace() {
        return new Workspace(WORKSPACE_ID, OWNER_ID, "运营分析", null, "ACTIVE", NOW, NOW);
    }

    private static Membership member(Long userId, Role role) {
        return new Membership(userId, WORKSPACE_ID, userId, role.name(), "ACTIVE", OWNER_ID, NOW, null);
    }
}
