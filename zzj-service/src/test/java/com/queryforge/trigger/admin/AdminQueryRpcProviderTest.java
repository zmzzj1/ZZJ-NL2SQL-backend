package com.queryforge.trigger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.queryforge.application.admin.AdminQueryService;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.zzj.common.admin.AdminActor;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminRpcErrorCode;
import com.zzj.common.admin.AdminRpcResult;
import com.zzj.common.admin.AdminUserPage;
import com.zzj.common.admin.AdminUserQuery;

/**
 * Provider 判权与失败语义：
 * 判权完全来自服务端持久化角色，调用方传入的 role 无效；内部异常不外泄。
 */
class AdminQueryRpcProviderTest {

    private final AdminQueryService adminQueryService = mock(AdminQueryService.class);
    private final AdminQueryRpcProvider provider = new AdminQueryRpcProvider(adminQueryService);

    @Test
    void listUsers_shouldReturnDataForPlatformAdmin() {
        when(adminQueryService.listUsers(any())).thenReturn(new AdminUserPage(List.of(), 0L, 1, 20));

        AdminRpcResult<AdminUserPage> result = provider.listUsers(new AdminActor(1L, "ADMIN"), new AdminUserQuery());

        assertThat(result.isOk()).isTrue();
        assertThat(result.getData().getTotal()).isZero();
    }

    @Test
    void listUsers_shouldRejectNonAdminEvenWhenActorClaimsAdmin() {
        when(adminQueryService.requirePlatformAdmin(1L)).thenThrow(
                new ApiException(ErrorCode.ADMIN_FORBIDDEN, HttpStatus.FORBIDDEN, "仅平台管理员可执行此操作"));

        // AdminActor 里的 role 由调用方提供：伪造为 ADMIN 也不能通过 Provider 判权。
        AdminRpcResult<AdminUserPage> result = provider.listUsers(new AdminActor(1L, "ADMIN"), new AdminUserQuery());

        assertThat(result.isOk()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AdminRpcErrorCode.FORBIDDEN);
    }

    @Test
    void listUsers_shouldRejectMissingActor() {
        when(adminQueryService.requirePlatformAdmin(null)).thenThrow(
                new ApiException(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "缺少登录主体"));

        AdminRpcResult<AdminUserPage> result = provider.listUsers(null, new AdminUserQuery());

        assertThat(result.getErrorCode()).isEqualTo(AdminRpcErrorCode.UNAUTHORIZED);
        org.mockito.Mockito.verify(adminQueryService, org.mockito.Mockito.never()).listUsers(any());
    }

    @Test
    void dashboard_shouldReportUnavailableInsteadOfFakeNumbers() {
        when(adminQueryService.requirePlatformAdmin(1L)).thenReturn(null);
        when(adminQueryService.dashboard()).thenThrow(new IllegalStateException("db down"));

        AdminRpcResult<AdminDashboardSummary> result = provider.dashboard(new AdminActor(1L, "ADMIN"));

        assertThat(result.isOk()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(AdminRpcErrorCode.UNAVAILABLE);
        assertThat(result.getData()).isNull();
    }
}
