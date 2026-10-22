package com.zzj.gateway;

/**
 * Sa-Token 会话键：与核心服务 {@code StpLogic} 的默认拼接完全一致。
 *
 * <p>键与 TTL 由核心服务写入，网关只读；不自行拼接新键、不延长过期、不写回。
 */
public final class SaTokenKeys {

    /** token -&gt; loginId（登录即写，注销/超时即删）。 */
    public static final String TOKEN_PREFIX = "satoken:login:token:";

    /** loginId -&gt; SaSession 快照（含 principalUserId/principalRole 等）。 */
    public static final String SESSION_PREFIX = "satoken:login:session:";

    private SaTokenKeys() {
    }

    public static String tokenKey(String tokenValue) {
        return TOKEN_PREFIX + tokenValue;
    }

    public static String sessionKey(String loginId) {
        return SESSION_PREFIX + loginId;
    }
}
