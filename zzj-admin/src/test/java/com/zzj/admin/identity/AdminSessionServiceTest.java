package com.zzj.admin.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import com.zzj.admin.web.AdminApiException;
import com.zzj.admin.web.AdminErrorCode;

/** 会话校验：只读复用核心服务会话，缺任一段即 401，存储异常即 503。 */
class AdminSessionServiceTest {

    private static final String SESSION_JSON = """
            {"id":"session-1","dataMap":{"principalUserId":"12","principalEmail":"a@example.com",
             "principalDisplayName":"Alice","principalRole":"ADMIN"}}
            """;

    @Test
    void resolve_shouldBuildPrincipalFromSharedSession() {
        RedissonClient client = fakeClient("token-1", "12", SESSION_JSON);
        AdminSessionService service = new AdminSessionService(() -> client);

        AdminPrincipal principal = service.resolve("token-1");

        assertThat(principal.userId()).isEqualTo(12L);
        assertThat(principal.role()).isEqualTo("ADMIN");
        assertThat(principal.isPlatformAdmin()).isTrue();
    }

    @Test
    void resolve_shouldRejectMissingToken() {
        AdminSessionService service = new AdminSessionService(() -> mock(RedissonClient.class));

        assertThatThrownBy(() -> service.resolve((String) null))
                .isInstanceOf(AdminApiException.class)
                .extracting(ex -> ((AdminApiException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void resolve_shouldRejectSessionWithoutPrincipalSnapshot() {
        RedissonClient client = fakeClient("token-1", "12", "{\"id\":\"session-1\",\"dataMap\":{}}");
        AdminSessionService service = new AdminSessionService(() -> client);

        assertThatThrownBy(() -> service.resolve("token-1"))
                .isInstanceOf(AdminApiException.class)
                .extracting(ex -> ((AdminApiException) ex).getErrorCode())
                .isEqualTo(AdminErrorCode.UNAUTHENTICATED);
    }

    @Test
    void resolve_shouldFailClosedWhenSessionStoreUnavailable() {
        RedissonClient client = mock(RedissonClient.class);
        when(client.<String>getBucket(anyString())).thenThrow(new IllegalStateException("redis down"));
        Supplier<RedissonClient> supplier = () -> client;
        AdminSessionService service = new AdminSessionService(supplier);

        assertThatThrownBy(() -> service.resolve("token-1"))
                .isInstanceOf(AdminApiException.class)
                .extracting(ex -> ((AdminApiException) ex).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @SuppressWarnings("unchecked")
    private static RedissonClient fakeClient(String token, String loginId, String sessionJson) {
        RBucket<String> tokenBucket = mock(RBucket.class);
        when(tokenBucket.get()).thenReturn(loginId);
        RBucket<String> sessionBucket = mock(RBucket.class);
        when(sessionBucket.get()).thenReturn(sessionJson);
        RedissonClient client = mock(RedissonClient.class);
        when(client.<String>getBucket(ArgumentMatchers.endsWith(token))).thenReturn(tokenBucket);
        when(client.<String>getBucket(ArgumentMatchers.endsWith(":" + loginId))).thenReturn(sessionBucket);
        return client;
    }
}
