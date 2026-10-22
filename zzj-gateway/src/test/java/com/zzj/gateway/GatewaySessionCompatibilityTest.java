package com.zzj.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;
import reactor.core.publisher.Mono;

/**
 * 会话兼容集成：用与核心服务同一版本/同一默认编解码的 Redisson 写入 Sa-Token 键，
 * 由网关校验逻辑读取——证明两进程共享同一条真实会话，而不是网关自造一套结论。
 *
 * <p>Redis 不可达时整体跳过（Assumptions），绝不把缺失标成通过。
 */
class GatewaySessionCompatibilityTest {

    private static RedissonClient writer;
    private static RedissonReactiveClient reader;
    private static SharedSessionValidationService service;

    @BeforeAll
    static void setUp() {
        writer = liveClient();
        reader = writer.reactive();
        service = new SharedSessionValidationService(() -> reader, Duration.ofSeconds(3));
    }

    @AfterAll
    static void tearDown() {
        if (writer != null) {
            writer.shutdown();
        }
    }

    private static RedissonClient liveClient() {
        org.junit.jupiter.api.Assumptions.assumeTrue(reachable(), "Redis 127.0.0.1:6379 不可用");
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setRetryAttempts(1)
                .setRetryInterval(100);
        return org.redisson.Redisson.create(config);
    }

    private static boolean reachable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 6379), 1000);
            return true;
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    private static String sessionJson(String loginId) {
        return "{\"@class\":\"cn.dev33.satoken.session.SaSession\",\"id\":\"" + loginId
                + "\",\"dataMap\":{\"principalUserId\":\"" + loginId
                + "\",\"principalEmail\":\"gateway@example.com\",\"principalRole\":\"USER\"}}";
    }

    @Test
    @DisplayName("同一条真实会话：token+session 两键齐全时网关判定已登录")
    void authenticatesRealSession() {
        String token = "gw-" + UUID.randomUUID();
        String loginId = "488";
        writer.getBucket(SaTokenKeys.tokenKey(token)).set(loginId);
        writer.getBucket(SaTokenKeys.sessionKey(loginId)).set(sessionJson(loginId));

        try {
            assertThat(service.validate(token).block()).isEqualTo(AuthOutcome.AUTHENTICATED);
        } finally {
            writer.getBucket(SaTokenKeys.tokenKey(token)).delete();
        }
    }

    @Test
    @DisplayName("只存在 session 键而 token 键已删（注销/过期）时判定未登录")
    void rejectsWhenTokenKeyGone() {
        String token = "gw-" + UUID.randomUUID();
        String loginId = "489";
        writer.getBucket(SaTokenKeys.sessionKey(loginId)).set(sessionJson(loginId));

        try {
            assertThat(service.validate(token).block()).isEqualTo(AuthOutcome.UNAUTHENTICATED);
        } finally {
            writer.getBucket(SaTokenKeys.sessionKey(loginId)).delete();
        }
    }

    @Test
    @DisplayName("token 键存在但会话快照缺失（TTL 部分回收）时判定未登录")
    void rejectsWhenSessionMissing() {
        String token = "gw-" + UUID.randomUUID();
        writer.getBucket(SaTokenKeys.tokenKey(token)).set("999999");

        try {
            assertThat(service.validate(token).block()).isEqualTo(AuthOutcome.UNAUTHENTICATED);
        } finally {
            writer.getBucket(SaTokenKeys.tokenKey(token)).delete();
        }
    }

    @Test
    @DisplayName("会话快照缺少主体字段（TTL 部分回收/异常写入）时判定未登录")
    void rejectsSessionWithoutPrincipal() {
        String token = "gw-" + UUID.randomUUID();
        String loginId = "491";
        writer.getBucket(SaTokenKeys.tokenKey(token)).set(loginId);
        writer.getBucket(SaTokenKeys.sessionKey(loginId))
                .set("{\"@class\":\"cn.dev33.satoken.session.SaSession\",\"dataMap\":{}}");

        try {
            assertThat(service.validate(token).block()).isEqualTo(AuthOutcome.UNAUTHENTICATED);
        } finally {
            writer.getBucket(SaTokenKeys.tokenKey(token)).delete();
            writer.getBucket(SaTokenKeys.sessionKey(loginId)).delete();
        }
    }

    @Test
    @DisplayName("会话值无法解析（编解码不一致/数据损坏）按不可用失败关闭")
    void failsClosedOnUnparsableSession() {
        String token = "gw-" + UUID.randomUUID();
        String loginId = "492";
        writer.getBucket(SaTokenKeys.tokenKey(token)).set(loginId);
        writer.getBucket(SaTokenKeys.sessionKey(loginId)).set("\u0001\u0002not-json");

        try {
            assertThat(service.validate(token).block()).isEqualTo(AuthOutcome.UNAVAILABLE);
        } finally {
            writer.getBucket(SaTokenKeys.tokenKey(token)).delete();
            writer.getBucket(SaTokenKeys.sessionKey(loginId)).delete();
        }
    }

    @Test
    @DisplayName("无 token / 空白 token 直接判定未登录，不访问 Redis")
    void rejectsBlankTokenWithoutLookup() {
        assertThat(service.validate(null).block()).isEqualTo(AuthOutcome.UNAUTHENTICATED);
        assertThat(service.validate("   ").block()).isEqualTo(AuthOutcome.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("会话存储不可达（Redis 故障）按不可用失败关闭，不冒充未登录也不放行")
    void failsClosedWhenStoreUnreachable() {
        // 指向未监听端口：建连必然失败，用于验证失败关闭而不是静默放行或异常外泄。
        java.util.function.Supplier<RedissonReactiveClient> dead = () -> {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://127.0.0.1:6390")
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(1)
                    .setRetryAttempts(1)
                    .setRetryInterval(100);
            return org.redisson.Redisson.create(config).reactive();
        };
        SharedSessionValidationService failing =
                new SharedSessionValidationService(dead, Duration.ofSeconds(3));
        assertThat(failing.validate("gw-" + UUID.randomUUID()).block())
                .isEqualTo(AuthOutcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("校验是只读的：不写键、不续期（TTL 由核心服务决定）")
    void validationDoesNotWrite() {
        String token = "gw-" + UUID.randomUUID();
        String loginId = "490";
        writer.getBucket(SaTokenKeys.tokenKey(token)).set(loginId, Duration.ofSeconds(60));
        writer.getBucket(SaTokenKeys.sessionKey(loginId)).set(sessionJson(loginId), Duration.ofSeconds(60));
        long ttlBefore = writer.getBucket(SaTokenKeys.tokenKey(token)).remainTimeToLive();

        assertThat(service.validate(token).block()).isEqualTo(AuthOutcome.AUTHENTICATED);

        long ttlAfter = writer.getBucket(SaTokenKeys.tokenKey(token)).remainTimeToLive();
        assertThat(ttlAfter).isLessThanOrEqualTo(ttlBefore);

        writer.getBucket(SaTokenKeys.tokenKey(token)).delete();
        writer.getBucket(SaTokenKeys.sessionKey(loginId)).delete();
    }
}
