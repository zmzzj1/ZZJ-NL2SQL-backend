package com.zzj.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.zzj.admin.identity.AdminPrincipal;
import com.zzj.admin.identity.AdminSessionService;
import com.zzj.admin.rpc.AdminQueryRpcClient;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminUserPage;
import com.zzj.common.admin.AdminUserQuery;
import com.zzj.common.admin.AdminUserSummary;

/**
 * 管理查询入口：判权、参数收敛、DTO 脱敏与依赖不可用的 HTTP 语义。
 *
 * <p>用 standalone MockMvc：本用例只验证 Web 层契约，
 * 不启动 Dubbo/Nacos（避免把"注册中心可用"变成单元测试的前置条件）。
 */
class AdminQueryControllerTest {

    private final AdminSessionService sessions = mock(AdminSessionService.class);
    private final AdminQueryRpcClient rpcClient = mock(AdminQueryRpcClient.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminQueryController(sessions, rpcClient))
            .setControllerAdvice(new AdminExceptionHandler())
            .build();

    @BeforeEach
    void resetStubs() {
        reset(sessions, rpcClient);
    }

    @Test
    void users_shouldReturnPageForPlatformAdmin() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class)))
                .thenReturn(new AdminPrincipal(1L, "admin@example.com", "Admin", "ADMIN"));
        when(rpcClient.listUsers(any(), any())).thenReturn(new AdminUserPage(
                List.of(new AdminUserSummary(9L, "u@example.com", "Uma", "USER", "2026-10-22 09:00:00")),
                1L, 1, 20));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].email").value("u@example.com"))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].password").doesNotExist());
    }

    @Test
    void users_shouldClampOversizedPageSize() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class)))
                .thenReturn(new AdminPrincipal(1L, "admin@example.com", "Admin", "ADMIN"));
        when(rpcClient.listUsers(any(), any())).thenReturn(new AdminUserPage(List.of(), 0L, 1, 100));

        mockMvc.perform(get("/api/v1/admin/users").param("size", "5000")).andExpect(status().isOk());

        ArgumentCaptor<AdminUserQuery> captor = ArgumentCaptor.forClass(AdminUserQuery.class);
        verify(rpcClient).listUsers(any(), captor.capture());
        assertThat(captor.getValue().getSize()).isEqualTo(AdminUserQuery.MAX_SIZE);
    }

    @Test
    void users_shouldRejectNonAdminWithoutCallingProvider() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class)))
                .thenReturn(new AdminPrincipal(2L, "u@example.com", "Uma", "USER"));

        // 伪造身份头不改变主体：判权只来自服务端会话。
        mockMvc.perform(get("/api/v1/admin/users").header("X-User-Id", "1").header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.ADMIN_FORBIDDEN.name()));

        verify(rpcClient, never()).listUsers(any(), any());
    }

    @Test
    void users_shouldRejectAnonymous() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenThrow(
                new AdminApiException(AdminErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "未登录或登录已失效"));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.UNAUTHENTICATED.name()));
    }

    @Test
    void dashboard_shouldReturnSummaryForPlatformAdmin() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class)))
                .thenReturn(new AdminPrincipal(1L, "admin@example.com", "Admin", "ADMIN"));
        when(rpcClient.dashboard(any())).thenReturn(
                new AdminDashboardSummary(3L, 1L, 2L, 10L, 4L, 1L, 5L, 2L, "2026-10-22 10:00:00"));

        mockMvc.perform(get("/api/v1/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userTotal").value(3))
                .andExpect(jsonPath("$.adminTotal").value(1))
                .andExpect(jsonPath("$.generatedAt").value("2026-10-22 10:00:00"));
    }

    @Test
    void dashboard_shouldReturn503WhenProviderUnavailable() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class)))
                .thenReturn(new AdminPrincipal(1L, "admin@example.com", "Admin", "ADMIN"));
        when(rpcClient.dashboard(any())).thenThrow(new AdminApiException(AdminErrorCode.SERVICE_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE, "管理服务依赖不可用"));

        mockMvc.perform(get("/api/v1/admin/dashboard"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(AdminErrorCode.SERVICE_UNAVAILABLE.name()));
    }
}
