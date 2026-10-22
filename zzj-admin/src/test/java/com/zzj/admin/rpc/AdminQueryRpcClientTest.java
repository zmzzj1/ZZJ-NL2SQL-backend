package com.zzj.admin.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.zzj.admin.web.AdminApiException;
import com.zzj.admin.web.AdminErrorCode;
import com.zzj.common.admin.AdminActor;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminQueryRpc;
import com.zzj.common.admin.AdminRpcErrorCode;
import com.zzj.common.admin.AdminRpcResult;
import com.zzj.common.admin.AdminUserPage;
import com.zzj.common.admin.AdminUserQuery;
import com.zzj.common.admin.AdminUserOperationResult;

/** RPC 语义到 HTTP 语义的映射：业务失败按码翻译，通道失败一律 503。 */
class AdminQueryRpcClientTest {

    private AdminQueryRpcClient client;
    private StubRpc stub;

    @BeforeEach
    void setUp() throws Exception {
        client = new AdminQueryRpcClient();
        stub = new StubRpc();
        Field field = AdminQueryRpcClient.class.getDeclaredField("adminQueryRpc");
        field.setAccessible(true);
        field.set(client, stub);
    }

    @Test
    void listUsers_shouldReturnDataOnSuccess() {
        stub.users = AdminRpcResult.success(new AdminUserPage(java.util.List.of(), 0L, 1, 20));

        assertThat(client.listUsers(new AdminActor(1L, "ADMIN"), new AdminUserQuery())).isNotNull();
    }

    @Test
    void listUsers_shouldMapForbiddenToHttp403() {
        stub.users = AdminRpcResult.failure(AdminRpcErrorCode.FORBIDDEN, "仅平台管理员");

        assertThatThrownBy(() -> client.listUsers(new AdminActor(2L, "USER"), new AdminUserQuery()))
                .isInstanceOf(AdminApiException.class)
                .satisfies(ex -> {
                    assertThat(((AdminApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((AdminApiException) ex).getErrorCode()).isEqualTo(AdminErrorCode.ADMIN_FORBIDDEN);
                });
    }

    @Test
    void dashboard_shouldMapUnauthorizedToHttp401() {
        stub.dashboard = AdminRpcResult.failure(AdminRpcErrorCode.UNAUTHORIZED, "登录主体已不存在");

        assertThatThrownBy(() -> client.dashboard(new AdminActor(99L, "ADMIN")))
                .isInstanceOf(AdminApiException.class)
                .satisfies(ex -> assertThat(((AdminApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void dashboard_shouldMapRpcFailureToHttp503() {
        stub.rpcException = new RpcException("No provider available");

        assertThatThrownBy(() -> client.dashboard(new AdminActor(1L, "ADMIN")))
                .isInstanceOf(AdminApiException.class)
                .satisfies(ex -> {
                    assertThat(((AdminApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((AdminApiException) ex).getErrorCode())
                            .isEqualTo(AdminErrorCode.SERVICE_UNAVAILABLE);
                });
    }

    private static final class StubRpc implements AdminQueryRpc {

        private AdminRpcResult<AdminUserPage> users;
        private AdminRpcResult<AdminDashboardSummary> dashboard;
        private RpcException rpcException;

        @Override
        public AdminRpcResult<AdminUserPage> listUsers(AdminActor actor, AdminUserQuery query) {
            if (rpcException != null) {
                throw rpcException;
            }
            return users;
        }

        @Override
        public AdminRpcResult<AdminDashboardSummary> dashboard(AdminActor actor) {
            if (rpcException != null) {
                throw rpcException;
            }
            return dashboard;
        }

        @Override
        public AdminRpcResult<AdminUserOperationResult> setUserStatus(AdminActor actor, Long userId, String status) {
            if (rpcException != null) {
                throw rpcException;
            }
            return AdminRpcResult.success(null);
        }

        @Override
        public AdminRpcResult<AdminUserOperationResult> setUserQuota(AdminActor actor, Long userId, Long tokenQuota) {
            if (rpcException != null) {
                throw rpcException;
            }
            return AdminRpcResult.success(null);
        }
    }
}
