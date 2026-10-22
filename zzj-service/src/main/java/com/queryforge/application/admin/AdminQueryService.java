package com.queryforge.application.admin;

import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.queryforge.application.model.AdminGuard;
import com.queryforge.domain.identity.UserEntity;
import com.queryforge.infrastructure.identity.SaTokenIdentityService;
import com.queryforge.infrastructure.persistence.ModelRepository;
import com.queryforge.infrastructure.persistence.UserRepository;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminUserOperationResult;
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
    /** P13-M3B：合法账号状态集合。 */
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    /** P13-M3B：用户级 Token 配额上限（防止溢出与异常大值）。 */
    private static final long MAX_TOKEN_QUOTA = 9_000_000_000_000L;

    private final UserRepository users;
    private final JdbcTemplate jdbcTemplate;
    private final AdminGuard adminGuard;
    private final SaTokenIdentityService identityService;
    private final ModelRepository modelRepository;

    public AdminQueryService(UserRepository users, JdbcTemplate jdbcTemplate, AdminGuard adminGuard,
                             SaTokenIdentityService identityService, ModelRepository modelRepository) {
        this.users = users;
        this.jdbcTemplate = jdbcTemplate;
        this.adminGuard = adminGuard;
        this.identityService = identityService;
        this.modelRepository = modelRepository;
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
        Map<Long, Long> quotas = modelRepository.findUserTokenQuotas(
                rows.stream().map(UserEntity::getId).toList());
        List<AdminUserSummary> items = rows.stream()
                .map(row -> new AdminUserSummary(row.getId(), row.getEmail(), row.getDisplayName(), row.getRole(),
                        row.getCreatedAt() == null ? null : TIMESTAMP.format(row.getCreatedAt()),
                        normalizeStatus(row.getStatus()), quotas.get(row.getId())))
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

    /**
     * P13-M3B：启用/停用用户。管理员身份以持久化角色重新判定；
     * 不允管理员停用自己（避免唯一管理员被锁死），停用会踢除目标所有会话。
     */
    public AdminUserOperationResult setUserStatus(Long actorId, Long userId, String status) {
        AppUserPrincipal actor = requirePlatformAdmin(actorId);
        if (userId == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "缺少用户 ID");
        }
        String normalized = normalizeStatus(status);
        if (normalized == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                    "非法的账号状态，仅允许 ACTIVE 或 DISABLED");
        }
        if (userId.equals(actor.id())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "不能修改自己的账号状态");
        }
        UserEntity target = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在"));
        users.setStatus(userId, normalized);
        if (STATUS_DISABLED.equals(normalized)) {
            // 立即让既有会话失效，禁用状态才能阻断敏感操作（rule 5）。
            identityService.forceLogout(userId);
        }
        modelRepository.audit(actor.id(), "SET_USER_STATUS", "USER", userId,
                "status=" + normalized, "SUCCESS");
        return new AdminUserOperationResult(userId, normalized,
                readUserTokenQuota(userId), target.getRole());
    }

    /**
     * P13-M3B：调整用户级 Token 配额。映射到 platform_quotas 的 USER 维度 token_limit；
     * 不影响其它维度或无关用户。
     */
    public AdminUserOperationResult setUserQuota(Long actorId, Long userId, Long tokenQuota) {
        AppUserPrincipal actor = requirePlatformAdmin(actorId);
        if (userId == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "缺少用户 ID");
        }
        if (tokenQuota == null || tokenQuota < 0 || tokenQuota > MAX_TOKEN_QUOTA) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                    "Token 配额必须为 0 至 " + MAX_TOKEN_QUOTA + " 之间的非负整数");
        }
        UserEntity target = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND, "用户不存在"));
        modelRepository.upsertQuota("USER", userId, null, null, tokenQuota, null, null, actor.id());
        modelRepository.audit(actor.id(), "SET_USER_QUOTA", "USER", userId,
                "tokenQuota=" + tokenQuota, "SUCCESS");
        return new AdminUserOperationResult(userId, normalizeStatus(target.getStatus()),
                tokenQuota, target.getRole());
    }

    private long readUserTokenQuota(Long userId) {
        return modelRepository.findQuota("USER", userId)
                .map(com.queryforge.infrastructure.persistence.ModelRepository.QuotaRow::tokenLimit)
                .orElse(0L);
    }

    private static String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        String trimmed = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (STATUS_ACTIVE.equals(trimmed) || STATUS_DISABLED.equals(trimmed)) {
            return trimmed;
        }
        return null;
    }
}
