package com.zzj.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.zzj.admin.identity.AdminPrincipal;
import com.zzj.admin.identity.AdminSessionService;
import com.zzj.admin.rpc.AdminQueryRpcClient;
import com.zzj.common.admin.AdminActor;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminUserOperationResult;
import com.zzj.common.admin.AdminUserPage;
import com.zzj.common.admin.AdminUserQuery;

/**
 * P13-M3A：只读管理查询入口（由 Gateway 精确路由转发，不直接对外暴露）。
 *
 * <p>两道判权：
 * <ol>
 *   <li>入口：先复用核心服务会话确认"谁在调用"，并要求会话角色是平台管理员；</li>
 *   <li>RPC：核心服务 Provider 再按数据库持久化角色判权一次，入参角色不具备效力。</li>
 * </ol>
 * 本控制器不接受任何身份头：伪造 {@code X-User-Id} 之类请求头不会改变 {@link AdminPrincipal}。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminQueryController {

    private final AdminSessionService sessions;
    private final AdminQueryRpcClient rpcClient;

    public AdminQueryController(AdminSessionService sessions, AdminQueryRpcClient rpcClient) {
        this.sessions = sessions;
        this.rpcClient = rpcClient;
    }

    @GetMapping("/users")
    public AdminUserPage users(HttpServletRequest request,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) String keyword) {
        AdminPrincipal principal = requirePlatformAdmin(request);
        // 分页上限由契约层强制（AdminUserQuery 构造器收敛），此处不做第二套规则。
        AdminUserQuery query = new AdminUserQuery(keyword, page, size);
        return rpcClient.listUsers(new AdminActor(principal.userId(), principal.role()), query);
    }

    @GetMapping("/dashboard")
    public AdminDashboardSummary dashboard(HttpServletRequest request) {
        AdminPrincipal principal = requirePlatformAdmin(request);
        return rpcClient.dashboard(new AdminActor(principal.userId(), principal.role()));
    }

    /**
     * P13-M3B：平台管理权限自检。未登录或普通用户由 {@link #requirePlatformAdmin} 拒绝（401/403）；
     * 管理员返回 isAdmin=true。前端据此决定是否展示管理操作入口。
     */
    @GetMapping("/check")
    public AdminCheckResponse check(HttpServletRequest request) {
        AdminPrincipal principal = requirePlatformAdmin(request);
        return new AdminCheckResponse(true, principal.role());
    }

    /**
     * P13-M3B：启用/停用用户。UserId 来自路径，状态来自请求体；真实更新由核心服务完成。
     */
    @PutMapping("/users/{userId}/status")
    public AdminUserOperationResult setUserStatus(HttpServletRequest request,
                                                 @PathVariable("userId") Long userId,
                                                 @RequestBody AdminUserStatusRequest body) {
        AdminPrincipal principal = requirePlatformAdmin(request);
        return rpcClient.setUserStatus(new AdminActor(principal.userId(), principal.role()), userId,
                body == null ? null : body.status());
    }

    /**
     * P13-M3B：调整用户级 Token 配额。真实写入 platform_quotas 的 USER 维度。
     */
    @PutMapping("/users/{userId}/quota")
    public AdminUserOperationResult setUserQuota(HttpServletRequest request,
                                               @PathVariable("userId") Long userId,
                                               @RequestBody AdminUserQuotaRequest body) {
        AdminPrincipal principal = requirePlatformAdmin(request);
        Long tokenQuota = body == null ? null : body.tokenQuota();
        return rpcClient.setUserQuota(new AdminActor(principal.userId(), principal.role()), userId, tokenQuota);
    }

    private AdminPrincipal requirePlatformAdmin(HttpServletRequest request) {
        AdminPrincipal principal = sessions.resolve(request);
        if (!principal.isPlatformAdmin()) {
            throw new AdminApiException(AdminErrorCode.ADMIN_FORBIDDEN, HttpStatus.FORBIDDEN,
                    "仅平台管理员可执行此操作");
        }
        return principal;
    }
}
