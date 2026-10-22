package com.zzj.admin.rpc;

import java.util.function.Supplier;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.zzj.admin.web.AdminApiException;
import com.zzj.admin.web.AdminErrorCode;
import com.zzj.common.admin.AdminActor;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminQueryRpc;
import com.zzj.common.admin.AdminRpcErrorCode;
import com.zzj.common.admin.AdminRpcResult;
import com.zzj.common.admin.AdminUserOperationResult;
import com.zzj.common.admin.AdminUserPage;
import com.zzj.common.admin.AdminUserQuery;

/**
 * P13-M3A：管理查询 RPC 客户端（Dubbo Consumer）。
 *
 * <p>只做两件事：发起真实 RPC，以及把 RPC 语义翻译成 HTTP 语义。
 * 通道不可用（无 Provider、超时、序列化失败）一律 503，不返回占位数据或空统计；
 * Provider 返回的业务失败按其错误码映射为 401/403/400。
 */
@Component
public class AdminQueryRpcClient {

    private static final Logger log = LoggerFactory.getLogger(AdminQueryRpcClient.class);

    @DubboReference(interfaceClass = AdminQueryRpc.class, version = AdminQueryRpc.VERSION,
            group = AdminQueryRpc.GROUP, check = false, timeout = 5000, retries = 0)
    private AdminQueryRpc adminQueryRpc;

    public AdminUserPage listUsers(AdminActor actor, AdminUserQuery query) {
        return unwrap(() -> adminQueryRpc.listUsers(actor, query));
    }

    public AdminDashboardSummary dashboard(AdminActor actor) {
        return unwrap(() -> adminQueryRpc.dashboard(actor));
    }

    public AdminUserOperationResult setUserStatus(AdminActor actor, Long userId, String status) {
        return unwrap(() -> adminQueryRpc.setUserStatus(actor, userId, status));
    }

    public AdminUserOperationResult setUserQuota(AdminActor actor, Long userId, Long tokenQuota) {
        return unwrap(() -> adminQueryRpc.setUserQuota(actor, userId, tokenQuota));
    }

    private <T> T unwrap(Supplier<AdminRpcResult<T>> invocation) {
        AdminRpcResult<T> result;
        try {
            result = invocation.get();
        } catch (RpcException ex) {
            // 无可用 Provider / 超时：不能降级成空数据，直接失败关闭。
            log.warn("admin_rpc_unavailable type={}", ex.getClass().getSimpleName());
            throw new AdminApiException(AdminErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "管理服务依赖不可用");
        }
        if (result == null) {
            throw new AdminApiException(AdminErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "管理服务依赖不可用");
        }
        if (result.isOk()) {
            return result.getData();
        }
        throw toHttp(result);
    }

    private static AdminApiException toHttp(AdminRpcResult<?> result) {
        AdminRpcErrorCode code = result.getErrorCode();
        String message = result.getMessage() == null ? "管理查询失败" : result.getMessage();
        if (code == AdminRpcErrorCode.UNAUTHORIZED) {
            return new AdminApiException(AdminErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, message);
        }
        if (code == AdminRpcErrorCode.FORBIDDEN) {
            return new AdminApiException(AdminErrorCode.ADMIN_FORBIDDEN, HttpStatus.FORBIDDEN, message);
        }
        if (code == AdminRpcErrorCode.BAD_REQUEST) {
            return new AdminApiException(AdminErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, message);
        }
        if (code == AdminRpcErrorCode.NOT_FOUND) {
            return new AdminApiException(AdminErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, message);
        }
        return new AdminApiException(AdminErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
