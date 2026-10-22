package com.queryforge.trigger.admin;

import com.queryforge.application.admin.AdminQueryService;
import com.queryforge.shared.error.ApiException;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import com.zzj.common.admin.AdminActor;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminQueryRpc;
import com.zzj.common.admin.AdminRpcErrorCode;
import com.zzj.common.admin.AdminRpcResult;
import com.zzj.common.admin.AdminUserPage;
import com.zzj.common.admin.AdminUserQuery;

/**
 * P13-M3A：管理查询 RPC Provider（由核心业务进程提供，Admin 侧消费）。
 *
 * <p>判权不信任入参：{@link AdminActor} 里的 userId 只是"谁在调用"，
 * 是否管理员一律由 {@link AdminQueryService} 按数据库持久化角色重新判定；
 * 会话快照 role 不具备任何效力，伪造 AdminActor 无法提升权限。
 *
 * <p>内部异常不外泄：只返回错误码与面向 Admin 的简短语义，
 * 不含堆栈、SQL 或用户隐私；数据不可用时返回 UNAVAILABLE，不返回占位统计。
 */
@DubboService(interfaceClass = AdminQueryRpc.class, version = AdminQueryRpc.VERSION, group = AdminQueryRpc.GROUP)
public class AdminQueryRpcProvider implements AdminQueryRpc {

    private static final Logger log = LoggerFactory.getLogger(AdminQueryRpcProvider.class);

    private final AdminQueryService adminQueryService;

    public AdminQueryRpcProvider(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @Override
    public AdminRpcResult<AdminUserPage> listUsers(AdminActor actor, AdminUserQuery query) {
        return guarded(actor, () -> adminQueryService.listUsers(query));
    }

    @Override
    public AdminRpcResult<AdminDashboardSummary> dashboard(AdminActor actor) {
        return guarded(actor, adminQueryService::dashboard);
    }

    private <T> AdminRpcResult<T> guarded(AdminActor actor, Supplier<T> supplier) {
        try {
            adminQueryService.requirePlatformAdmin(actor == null ? null : actor.getUserId());
            return AdminRpcResult.success(supplier.get());
        } catch (ApiException ex) {
            // 只回传错误码语义，不携带用户数据与内部细节。
            AdminRpcErrorCode code = ex.getStatus() == HttpStatus.UNAUTHORIZED
                    ? AdminRpcErrorCode.UNAUTHORIZED
                    : AdminRpcErrorCode.FORBIDDEN;
            return AdminRpcResult.failure(code, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("admin_rpc_query_failed at={}", LocalDateTime.now(), ex);
            return AdminRpcResult.failure(AdminRpcErrorCode.UNAVAILABLE, "管理数据查询失败");
        }
    }
}
