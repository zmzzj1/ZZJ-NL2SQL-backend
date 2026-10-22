package com.queryforge.infrastructure.identity;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import java.util.Optional;
import org.springframework.stereotype.Component;
import com.queryforge.domain.identity.AppUserPrincipal;

/**
 * P12 S2：Sa-Token 身份服务——应用内<b>唯一</b>的可信 Principal 来源。
 *
 * <p>登录态只存在于 Redis（{@link RedissonSaTokenDao}）：token 校验、loginId 解析、
 * 会话读取全部走 Sa-Token 的服务端存储；HTTP 会话（JSESSIONID）不再承载任何身份，
 * 不存在两套可互相绕过的身份体系。
 *
 * <p>身份字段（userId/email/displayName/role）以快照形式随登录写入 Sa-Token 会话，
 * 与迁移前 Spring Security 会话内缓存 Authentication 的语义一致：改密/改角色在下次登录
 * 前不主动传播（当前基线同样如此），注销立即失效。
 */
@Component
public class SaTokenIdentityService {

    private static final String SESSION_USER_ID = "principalUserId";
    private static final String SESSION_EMAIL = "principalEmail";
    private static final String SESSION_DISPLAY_NAME = "principalDisplayName";
    private static final String SESSION_ROLE = "principalRole";

    /**
     * 登录成功后建立 Sa-Token 会话并写入主体快照。
     * 由已通过 Spring Security 密码校验的调用方触发，本方法不再做任何凭证判断。
     */
    public void establish(AppUserPrincipal principal) {
        StpUtil.login(principal.id());
        SaSession session = StpUtil.getSession();
        session.set(SESSION_USER_ID, String.valueOf(principal.id()));
        session.set(SESSION_EMAIL, principal.email());
        session.set(SESSION_DISPLAY_NAME, principal.displayName());
        session.set(SESSION_ROLE, principal.role());
    }

    /** 注销：删除 token 与账号会话，旧 token 立即失效。 */
    public void invalidate() {
        StpUtil.logout();
    }

    /**
     * P13-M3B：强制踢除指定用户的所有登录会话（停用账号时调用，使既有会话立即失效）。
     * 登录 id 即用户主键，与 {@link #establish} 写入的一致；kickout 后该用户旧的 token 全部作废。
     */
    public void forceLogout(Long userId) {
        if (userId == null) {
            return;
        }
        StpUtil.kickout(userId);
    }

    /**
     * 从当前请求解析登录主体：cookie/header 中的 token 只是"材料"，
     * userId 等身份字段一律以 Redis 中的服务端会话为准。
     *
     * <p>token 无效/过期、会话被 TTL 回收、会话缺少主体快照，均返回 empty（fail-closed），
     * 由上层按未登录处理；Redis 故障抛出的异常向上传播，由过滤器统一转为未登录。
     */
    public Optional<AppUserPrincipal> resolveCurrentRequest() {
        String tokenValue = StpUtil.getTokenValue();
        if (tokenValue == null || tokenValue.isBlank()) {
            return Optional.empty();
        }
        Object loginId = StpUtil.getLoginIdByToken(tokenValue);
        if (loginId == null) {
            return Optional.empty();
        }
        // createNew=false：会话不存在时返回 null，不顺手创建空会话。
        SaSession session = StpUtil.getSessionByLoginId(loginId, false);
        if (session == null) {
            return Optional.empty();
        }
        String userId = stringOrNull(session.get(SESSION_USER_ID));
        if (userId == null) {
            // 会话存在但无主体快照（异常写入/被 TTL 部分回收）：不发放身份。
            return Optional.empty();
        }
        String email = stringOrNull(session.get(SESSION_EMAIL));
        if (email == null) {
            return Optional.empty();
        }
        String displayName = stringOrNull(session.get(SESSION_DISPLAY_NAME));
        String role = stringOrNull(session.get(SESSION_ROLE));
        return Optional.of(new AppUserPrincipal(Long.valueOf(userId), email, displayName, null, role));
    }

    private static String stringOrNull(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
