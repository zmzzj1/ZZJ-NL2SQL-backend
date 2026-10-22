package com.zzj.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.zzj.admin.identity.AdminPrincipal;
import com.zzj.admin.identity.AdminSessionService;
import com.zzj.admin.rpc.AdminQueryRpcClient;
import com.zzj.common.admin.AdminActor;
import com.zzj.common.admin.AdminDashboardSummary;
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

    private AdminPrincipal requirePlatformAdmin(HttpServletRequest request) {
        AdminPrincipal principal = sessions.resolve(request);
        if (!principal.isPlatformAdmin()) {
            throw new AdminApiException(AdminErrorCode.ADMIN_FORBIDDEN, HttpStatus.FORBIDDEN,
                    "仅平台管理员可执行此操作");
        }
        return principal;
    }
}
