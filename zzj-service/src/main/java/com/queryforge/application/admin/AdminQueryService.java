package com.queryforge.application.admin;

import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.queryforge.application.model.AdminGuard;
import com.queryforge.domain.identity.UserEntity;
import com.queryforge.infrastructure.persistence.UserRepository;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminUserPage;
import com.zzj.common.admin.AdminUserQuery;
import com.zzj.common.admin.AdminUserSummary;

/**
 * P13-M3A：管理查询应用服务——为 Admin 的受控 RPC 查询提供真实数据。
 *
 * <p>只读查询，全部走已有持久化（用户走 {@link UserRepository}，统计走管理库 JdbcTemplate），
 * 不重建第二套用户数据、不做写操作。返回的 DTO 只含账号元数据与可核实计数，
 * 不含 passwordHash、Token、API Key 或数据源凭据。
 */
@Service
public class AdminQueryService {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String SUCCESS_STATUS = "SUCCESS";

    private final UserRepository users;
    private final JdbcTemplate jdbcTemplate;
    private final AdminGuard adminGuard;

    public AdminQueryService(UserRepository users, JdbcTemplate jdbcTemplate, AdminGuard adminGuard) {
        this.users = users;
        this.jdbcTemplate = jdbcTemplate;
        this.adminGuard = adminGuard;
    }

    /**
     * 以业务库中的持久化角色重新判权：会话快照里的角色只作为线索，判权依据始终是数据库。
     * 用户不存在与无管理员权限分别抛错，Admin 侧映射为 401 / 403。
     */
    public AppUserPrincipal requirePlatformAdmin(Long userId) {
        if (userId == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "缺少登录主体");
        }
        AppUserPrincipal principal = users.findPrincipalById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
                        "登录主体已不存在"));
        adminGuard.requireAdmin(principal);
        return principal;
    }

    public AdminUserPage listUsers(AdminUserQuery query) {
        AdminUserQuery safeQuery = query == null ? new AdminUserQuery() : query;
        long total = users.countByKeyword(safeQuery.getKeyword());
        List<UserEntity> rows = users.pageByKeyword(safeQuery.getKeyword(), safeQuery.getPage(), safeQuery.getSize());
        List<AdminUserSummary> items = rows.stream()
                .map(row -> new AdminUserSummary(row.getId(), row.getEmail(), row.getDisplayName(), row.getRole(),
                        row.getCreatedAt() == null ? null : TIMESTAMP.format(row.getCreatedAt())))
                .toList();
        return new AdminUserPage(items, total, safeQuery.getPage(), safeQuery.getSize());
    }

    public AdminDashboardSummary dashboard() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        return new AdminDashboardSummary(
                count("SELECT COUNT(*) FROM users"),
                count("SELECT COUNT(*) FROM users WHERE role = ?", ADMIN_ROLE),
                count("""
                        SELECT COUNT(*) FROM (SELECT user_id FROM query_history WHERE executed_at >= ?
                                              UNION
                                              SELECT user_id FROM agent_runs WHERE created_at >= ?) activity
                        """, since, since),
                count("SELECT COUNT(*) FROM query_history"),
                count("SELECT COUNT(*) FROM query_history WHERE executed_at >= ?", since),
                count("SELECT COUNT(*) FROM query_history WHERE executed_at >= ? AND status <> ?", since,
                        SUCCESS_STATUS),
                count("SELECT COUNT(*) FROM agent_runs"),
                count("SELECT COUNT(*) FROM agent_runs WHERE created_at >= ?", since),
                TIMESTAMP.format(LocalDateTime.now()));
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
