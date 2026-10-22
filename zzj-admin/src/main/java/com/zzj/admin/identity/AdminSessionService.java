package com.zzj.admin.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.zzj.admin.web.AdminApiException;
import com.zzj.admin.web.AdminErrorCode;

/**
 * P13-M3A：管理服务登录态校验——复用核心服务已建立的 Sa-Token 会话，不建立第二套身份。
 *
 * <p>校验链与核心服务 {@code SaTokenIdentityService.resolveCurrentRequest()}、Gateway 完全一致：
 * 读 {@code satoken} Cookie → 查 {@code satoken:login:token:<token>} 取 loginId →
 * 查 {@code satoken:login:session:<loginId>} 取主体快照；任一段缺失即 401。
 *
 * <p>失败关闭：Redis 抛错/超时按 503 处理，绝不因存储异常把请求当成已登录。
 * 只读：不写、不续期、不缓存判定。
 */
@Service
public class AdminSessionService {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionService.class);

    /** Sa-Token 键前缀：与核心服务默认拼接一致，管理服务只读不写。 */
    private static final String TOKEN_PREFIX = "satoken:login:token:";
    private static final String SESSION_PREFIX = "satoken:login:session:";
    private static final String SESSION_USER_ID = "principalUserId";
    private static final String SESSION_EMAIL = "principalEmail";
    private static final String SESSION_DISPLAY_NAME = "principalDisplayName";
    private static final String SESSION_ROLE = "principalRole";
    private static final String TOKEN_COOKIE = "satoken";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Supplier<RedissonClient> redis;

    public AdminSessionService(Supplier<RedissonClient> redis) {
        this.redis = redis;
    }

    /** 从请求解析登录主体；未登录抛 401，会话存储不可用抛 503。 */
    public AdminPrincipal resolve(HttpServletRequest request) {
        return resolve(readTokenCookie(request));
    }

    public AdminPrincipal resolve(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            throw unauthenticated();
        }
        String loginId;
        String sessionJson;
        try {
            RedissonClient client = redis.get();
            loginId = client.<String>getBucket(TOKEN_PREFIX + tokenValue).get();
            if (loginId == null || loginId.isBlank()) {
                throw unauthenticated();
            }
            sessionJson = client.<String>getBucket(SESSION_PREFIX + loginId).get();
        } catch (AdminApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 不记录 token/会话值，只记录事件类别。
            log.warn("admin_session_lookup_failed outcome=UNAVAILABLE type={} message={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            throw new AdminApiException(AdminErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "会话服务不可用");
        }
        if (sessionJson == null || sessionJson.isBlank()) {
            throw unauthenticated();
        }
        return parse(sessionJson);
    }

    private static AdminPrincipal parse(String sessionJson) {
        JsonNode data;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(sessionJson);
            data = root.isObject() && root.has("dataMap") ? root.get("dataMap") : root;
        } catch (Exception ex) {
            log.warn("admin_session_parse_failed");
            throw unauthenticated();
        }
        String userId = text(data, SESSION_USER_ID);
        String role = text(data, SESSION_ROLE);
        if (userId == null || role == null) {
            throw unauthenticated();
        }
        return new AdminPrincipal(Long.parseLong(userId), text(data, SESSION_EMAIL),
                text(data, SESSION_DISPLAY_NAME), role);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static String readTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static AdminApiException unauthenticated() {
        return new AdminApiException(AdminErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "未登录或登录已失效");
    }
}
