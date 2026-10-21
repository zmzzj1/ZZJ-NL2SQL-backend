package com.queryforge.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.integration.llm.LlmProperties;
import com.queryforge.integration.llm.LlmStream;
import com.queryforge.integration.llm.ModelTransport;
import com.queryforge.model.ModelEntities.Profile;
import com.queryforge.model.ModelEntities.Provider;
import com.queryforge.model.ModelRouter.CallRequirement;
import com.queryforge.model.ModelRouter.RouteDecision;
import com.queryforge.shared.error.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P11 修正验证：无论底层传输层是 Http 还是 Spring AI，{@link RoutedLlmClient} 都必须先经
 * {@code ModelRouter} 路由、再经治理链路（审计/熔断/重试/故障转移）调用传输层，绝不静默绕过 P9。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoutedLlmClient 与可替换传输层：路由/治理不因传输层替换被绕过")
class RoutedLlmClientTransportTest {

    @Mock
    private ModelRouter router;
    @Mock
    private ModelCatalogService catalog;
    @Mock
    private ModelRepository repository;
    @Mock
    private ModelTransport transport;

    private InvocationRecorder recorder;
    private RoutedLlmClient client;

    private static Profile profile(long id) {
        return new Profile(id, 10L, "model-" + id, "CHAT", true, false, false, 8192, 2048, null,
                "ACTIVE", true, null, null);
    }

    private static Provider provider() {
        return new Provider(10L, "p", "OPENAI_COMPAT", "http://p.example/v1", "cipher", true, 1L, null, null);
    }

    @BeforeEach
    void setUp() {
        recorder = new InvocationRecorder(repository, new ModelHealthRegistry());
        client = new RoutedLlmClient(router, catalog, recorder,
                new LlmProperties("http://127.0.0.1:0/v1", "key", "default-model",
                        Duration.ofSeconds(5), Duration.ofSeconds(10)),
                transport);
    }

    @Test
    @DisplayName("先路由后传输，且传输层使用被选中的 Provider/Model（不绕过 P9 路由）")
    void routesBeforeTransport_andUsesSelectedProfile() throws Exception {
        Profile p = profile(1L);
        Provider prov = provider();
        when(router.route(any(), anyLong(), any(), any(CallRequirement.class)))
                .thenReturn(new RouteDecision(p, prov, RouteStrategy.ROUND_ROBIN, ""));
        when(catalog.requireProvider(10L)).thenReturn(prov);
        when(catalog.decryptKey(prov)).thenReturn("key");
        when(transport.stream(eq("http://p.example/v1"), eq("key"), eq("model-1"), eq("sys"), eq("user")))
                .thenReturn(new ModelTransport.Response(200, Stream.of("data: {\"choices\":[]}")));

        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(1L, null, null, null, null));
        LlmStream stream = client.openChat("sys", "user");
        ModelRouteContextHolder.clear();

        InOrder order = inOrder(router, transport);
        order.verify(router).route(any(), anyLong(), any(), any(CallRequirement.class));
        order.verify(transport).stream(eq("http://p.example/v1"), eq("key"), eq("model-1"), eq("sys"), eq("user"));
        assertThat(stream).isNotNull();
    }

    @Test
    @DisplayName("传输层 429 触发 fallback（治理链路仍生效，不静默绕过后备）")
    void retryableStatusTriggersFallback() throws Exception {
        Profile primary = profile(1L);
        Profile fallback = profile(2L);
        Provider prov = provider();
        when(router.route(any(), anyLong(), any(), any(CallRequirement.class)))
                .thenReturn(new RouteDecision(primary, prov, RouteStrategy.ROUND_ROBIN, "", List.of(fallback)));
        when(catalog.requireProvider(10L)).thenReturn(prov);
        when(catalog.decryptKey(prov)).thenReturn("key");
        when(transport.stream(eq("http://p.example/v1"), eq("key"), eq("model-1"), anyString(), anyString()))
                .thenReturn(new ModelTransport.Response(429, Stream.of()));
        when(transport.stream(eq("http://p.example/v1"), eq("key"), eq("model-2"), anyString(), anyString()))
                .thenReturn(new ModelTransport.Response(200, Stream.of("data: ok")));

        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(1L, null, null, null, null));
        LlmStream stream = client.openChat("sys", "user");
        ModelRouteContextHolder.clear();

        verify(transport, times(2)).stream(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(stream).isNotNull();
    }

    @Test
    @DisplayName("非可重试 400 不重试并抛错（不静默绕过 P9 失败语义）")
    void nonRetryableStatusStops() throws Exception {
        Profile p = profile(1L);
        Provider prov = provider();
        when(router.route(any(), anyLong(), any(), any(CallRequirement.class)))
                .thenReturn(new RouteDecision(p, prov, RouteStrategy.ROUND_ROBIN, ""));
        when(catalog.requireProvider(10L)).thenReturn(prov);
        when(catalog.decryptKey(prov)).thenReturn("key");
        when(transport.stream(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ModelTransport.Response(400, Stream.of()));

        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(1L, null, null, null, null));
        assertThrows(ApiException.class, () -> client.openChat("sys", "user"));
        ModelRouteContextHolder.clear();

        verify(transport, times(1)).stream(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
