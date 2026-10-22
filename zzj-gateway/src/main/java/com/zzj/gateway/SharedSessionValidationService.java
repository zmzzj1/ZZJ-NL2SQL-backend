package com.zzj.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Supplier;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * P13-M2：网关登录态校验——只复用核心服务已建立的 Sa-Token 会话，不建立第二套身份。
 *
 * <p>校验链与 {@code SaTokenIdentityService.resolveCurrentRequest()} 一致：
 * 读 {@code satoken} Cookie → 查 {@code satoken:login:token:<token>} 取 loginId →
 * 查 {@code satoken:login:session:<loginId>} 取主体快照。缺少任一段即视为未登录。
 *
 * <p>失败关闭：Redis 抛错、超时、值类型异常或会话缺快照，一律按未登录/不可用处理，
 * 绝不因存储异常放行受保护请求；也不在网关侧缓存判定结果，避免撤销或过期后旧结论复活。
 *
 * <p>只读：不写、不续期、不更新 TTL，TTL 语义仍由核心服务（{@code active-timeout: -1}）决定。
 *
 * <p>连接惰性：Redisson 客户端在<b>首次校验</b>时才建立，因此 Redis 晚于网关启动、
 * 或运行中不可用，都不会让网关整体起不来或把请求误放行——只会得到 503 失败关闭。
 */
@Service
public class SharedSessionValidationService {

    private static final Logger log = LoggerFactory.getLogger(SharedSessionValidationService.class);

    /** 会话快照中必须存在的主体字段，与核心服务 {@code resolveCurrentRequest} 的取值一致。 */
    private static final String SESSION_USER_ID = "principalUserId";
    private static final String SESSION_ROLE = "principalRole";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Supplier<RedissonReactiveClient> redis;
    private final Duration readTimeout;

    public SharedSessionValidationService(Supplier<RedissonReactiveClient> redis,
                                          Duration gatewaySessionReadTimeout) {
        this.redis = redis;
        this.readTimeout = gatewaySessionReadTimeout == null || gatewaySessionReadTimeout.isZero()
                ? Duration.ofSeconds(2)
                : gatewaySessionReadTimeout;
    }

    /** 校验 cookie 中的 token 是否对应一条真实、未撤销的核心服务会话。 */
    public Mono<AuthOutcome> validate(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return Mono.just(AuthOutcome.UNAUTHENTICATED);
        }
        return readString(SaTokenKeys.tokenKey(tokenValue))
                .flatMap(loginId -> readString(SaTokenKeys.sessionKey(loginId))
                        .filter(SharedSessionValidationService::hasPrincipalSnapshot)
                        .map(sessionJson -> AuthOutcome.AUTHENTICATED)
                        .defaultIfEmpty(AuthOutcome.UNAUTHENTICATED))
                .defaultIfEmpty(AuthOutcome.UNAUTHENTICATED)
                .onErrorResume(ex -> {
                    // 不记录 token/会话值，只记录事件类别与结果。
                    log.warn("gateway_session_lookup_failed outcome=UNAVAILABLE type={}",
                            ex.getClass().getSimpleName());
                    return Mono.just(AuthOutcome.UNAVAILABLE);
                });
    }

    /**
     * 会话快照校验：会话值必须能解析出主体（userId + role），否则按未登录。
     * 只判定"快照是否完整"，不把会话内容当授权依据——资源授权仍在核心服务。
     */
    private static boolean hasPrincipalSnapshot(String sessionJson) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(sessionJson);
            JsonNode data = root.isObject() && root.has("dataMap") ? root.get("dataMap") : root;
            return data != null
                    && data.path(SESSION_USER_ID).asText(null) != null
                    && data.path(SESSION_ROLE).asText(null) != null;
        } catch (Exception ex) {
            // 反序列化失败（编解码不一致/数据损坏）按失败关闭处理，不当作已登录。
            throw new SessionStoreException(ex);
        }
    }

    /**
     * 会话读取：Redisson 响应式接口在连接失败时是<b>同步抛错</b>（实现用 {@code Mono.fromFuture}，
     * future 在返回前就已执行），异常不会变成 Mono 错误信号，会直接炸穿 {@code onErrorResume}。
     * 因此这里统一：
     * <ol>
     *   <li>{@code fromCallable + subscribeOn(boundedElastic)}：把同步抛错转成错误信号，
     *       并且 Redis I/O 不占网关事件循环；</li>
     *   <li>{@code timeout}：读超时按不可用处理；</li>
     *   <li>{@code onErrorResume}：统一转为可解释的失败关闭信号。</li>
     * </ol>
     */
    private Mono<String> readString(String key) {
        return Mono.fromCallable(() -> redis.get().<String>getBucket(key).get())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(value -> value)
                .flatMap(value -> value == null || value.isBlank() ? Mono.<String>empty() : Mono.just(value))
                .timeout(readTimeout)
                .onErrorResume(ex -> Mono.error(new SessionStoreException(ex)));
    }

    /** 会话存储不可用/数据异常的内部信号：只携带原因类型，不含任何键或值。 */
    private static final class SessionStoreException extends RuntimeException {

        private SessionStoreException(Throwable cause) {
            super("session store failure", cause);
        }
    }
}
