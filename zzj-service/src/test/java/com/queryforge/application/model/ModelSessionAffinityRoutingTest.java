package com.queryforge.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.domain.model.ModelEntities.Profile;
import com.queryforge.domain.model.ModelEntities.Provider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.queryforge.application.model.ModelRouter.RouteDecision;
import com.queryforge.application.model.ModelRouter.CallRequirement;
import com.queryforge.domain.model.ModelRouteContextHolder;
import com.queryforge.domain.model.RouteStrategy;
import com.queryforge.infrastructure.model.InvocationRecorder;
import com.queryforge.infrastructure.persistence.ModelRepository;
import com.queryforge.infrastructure.redis.ModelSessionAffinity;

/**
 * P12 S4 场景三：会话亲和性命中与豁免的单元验证（P1219 逻辑部分）。
 * 绑定模型仍合格 → SESSION_AFFINITY 复用；绑定模型熔断/未授权 → 按正常策略路由（不降低准入）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("模型会话亲和性路由")
class ModelSessionAffinityRoutingTest {

    @Mock
    private ModelRepository repository;

    @Mock
    private ModelCatalogService catalog;

    private ModelHealthRegistry health;
    private ModelRouter router;

    private static Profile profile(long id, long providerId) {
        return new Profile(id, providerId, "model-" + id, "CHAT", true, true, true,
                8192, 2048, null, "ACTIVE", true, null, null);
    }

    @BeforeEach
    void setUp() {
        health = new ModelHealthRegistry();
        router = new ModelRouter(repository, catalog, health);
        when(catalog.requireProvider(10L)).thenReturn(new Provider(
                10L, "p", "OPENAI_COMPAT", "https://x.com/v1", "cipher", true, 1L, null, null));
    }

    @Test
    @DisplayName("绑定模型仍合格：优先复用，策略记为 SESSION_AFFINITY")
    void reusesEligibleBoundProfile() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L), profile(2L, 10L)));
        when(repository.isGranted(1L, "USER", 1L)).thenReturn(true);
        when(repository.isGranted(2L, "USER", 1L)).thenReturn(true);

        RouteDecision decision = router.routeWithAffinity(
                null, 1L, null, CallRequirement.chat(true, false, false), 2L);

        assertThat(decision.profile().id()).isEqualTo(2L);
        assertThat(decision.strategy()).isEqualTo(RouteStrategy.SESSION_AFFINITY);
        assertThat(decision.filterReason()).contains("session-affinity:2");
    }

    @Test
    @DisplayName("绑定模型熔断 OPEN：不降低准入，按正常策略路由到其他模型")
    void ignoresBoundProfileWhenCircuitOpen() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L), profile(2L, 10L)));
        when(repository.isGranted(1L, "USER", 1L)).thenReturn(true);
        when(repository.isGranted(2L, "USER", 1L)).thenReturn(true);
        for (int i = 0; i < 5; i++) {
            health.recordFailure(2L, 100);
        }

        RouteDecision decision = router.routeWithAffinity(
                null, 1L, null, CallRequirement.chat(true, false, false), 2L);

        assertThat(decision.profile().id()).isEqualTo(1L);
        assertThat(decision.strategy()).isNotEqualTo(RouteStrategy.SESSION_AFFINITY);
    }

    @Test
    @DisplayName("绑定模型未授权：按正常策略路由，不借用绑定越权")
    void ignoresBoundProfileWhenUnauthorized() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L), profile(2L, 10L)));
        when(repository.isGranted(1L, "USER", 1L)).thenReturn(true);
        when(repository.isGranted(2L, "USER", 1L)).thenReturn(false);

        RouteDecision decision = router.routeWithAffinity(
                null, 1L, null, CallRequirement.chat(true, false, false), 2L);

        assertThat(decision.profile().id()).isEqualTo(1L);
        assertThat(decision.strategy()).isNotEqualTo(RouteStrategy.SESSION_AFFINITY);
    }

    @Test
    @DisplayName("无绑定：行为与普通 route 一致")
    void withoutAffinityBehavesLikeRoute() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L)));
        when(repository.isGranted(1L, "USER", 1L)).thenReturn(true);

        RouteDecision decision = router.routeWithAffinity(
                null, 1L, null, CallRequirement.chat(true, false, false), null);

        assertThat(decision.profile().id()).isEqualTo(1L);
        assertThat(decision.strategy()).isEqualTo(RouteStrategy.ROUND_ROBIN);
    }

    @Test
    @DisplayName("RoutedLlmClient：先解析会话绑定再路由，成功后按会话刷新绑定")
    void clientResolvesBeforeRoutingAndBindsAfter() throws Exception {
        ModelSessionAffinity affinity = org.mockito.Mockito.mock(ModelSessionAffinity.class);
        InvocationRecorder recorder = org.mockito.Mockito.mock(InvocationRecorder.class);
        com.queryforge.domain.model.ModelTransport transport =
                org.mockito.Mockito.mock(com.queryforge.domain.model.ModelTransport.class);
        // 真实 router（依赖 mock repository/catalog）：两个授权模型，绑定 2L 应被复用。
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L), profile(2L, 10L)));
        when(repository.isGranted(1L, "USER", 1L)).thenReturn(true);
        when(repository.isGranted(2L, "USER", 1L)).thenReturn(true);
        when(affinity.resolve(1L, 100L)).thenReturn(Optional.of(2L));
        when(recorder.newCallId()).thenReturn("call-1");
        when(catalog.decryptKey(org.mockito.ArgumentMatchers.any())).thenReturn("key");
        when(transport.stream("https://x.com/v1", "key", "model-2", "sys", "user"))
                .thenReturn(new com.queryforge.domain.model.ModelTransport.Response(
                        200, java.util.stream.Stream.of("data: ok")));

        RoutedLlmClient client = new RoutedLlmClient(
                router, catalog, recorder,
                new com.queryforge.domain.model.LlmProperties("https://x.com/v1", "key", "model-2",
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(10)),
                transport, affinity);

        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(1L, null, null, 5L, 9L, 100L));
        try {
            client.openChat("sys", "user");
        } finally {
            ModelRouteContextHolder.clear();
        }

        verify(affinity).resolve(1L, 100L);
        verify(affinity).bind(1L, 100L, 2L);
    }
}
