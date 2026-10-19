package com.queryforge.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.integration.llm.LlmProperties;
import com.queryforge.model.ModelEntities.Profile;
import com.queryforge.model.ModelEntities.Provider;
import com.queryforge.model.ModelRouter.CallRequirement;
import com.queryforge.model.ModelRouter.RouteDecision;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoutedLlmClient 有界重试与故障转移")
class RoutedLlmClientRetryTest {

    @Mock
    private ModelRouter router;
    @Mock
    private ModelCatalogService catalog;
    @Mock
    private ModelRepository repository;

    private ModelHealthRegistry health;
    private InvocationRecorder recorder;
    private LlmProperties properties;
    private RoutedLlmClient client;

    private HttpServer server;
    private AtomicInteger requestCount;
    private String baseUrl;

    private static Profile profile(long id) {
        return new Profile(id, 10L, "model-" + id, "CHAT", true, false, false, 8192, 2048, null,
                "ACTIVE", true, null, null);
    }

    private static Provider provider() {
        return new Provider(10L, "p", "OPENAI_COMPAT", "http://127.0.0.1:PORT/v1", "cipher", true, 1L, null, null);
    }

    @BeforeEach
    void setUp() throws Exception {
        health = new ModelHealthRegistry();
        recorder = new InvocationRecorder(repository, health);
        properties = new LlmProperties("http://127.0.0.1:0/v1", "key", "default-model",
                Duration.ofSeconds(5), Duration.ofSeconds(10));
        client = new RoutedLlmClient(router, catalog, recorder, properties, new ObjectMapper());
        requestCount = new AtomicInteger(0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer(java.util.function.Function<Integer, Integer> statusByRequest) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        server.createContext("/v1/chat/completions", exchange -> {
            int n = requestCount.incrementAndGet();
            int status = statusByRequest.apply(n);
            byte[] body;
            if (status == 200) {
                body = "data: {\"choices\":[{\"delta\":{\"content\":\"SELECT 1\"}}]}\n\ndata: [DONE]\n\n".getBytes();
            } else {
                body = ("{\"error\":\"boom\"}").getBytes();
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @Test
    @DisplayName("5xx 后切换 fallback 成功（故障转移）")
    void fallsBackOnServerError() throws Exception {
        // 第 1 次 503（首选），第 2 次 200（fallback）
        startServer(n -> n == 1 ? 503 : 200);
        Provider prov = new Provider(10L, "p", "OPENAI_COMPAT", baseUrl, "cipher", true, 1L, null, null);
        Profile primary = profile(1L);
        Profile fallback = profile(2L);
        when(router.route(null, 1L, null, CallRequirement.chat(true, false, false)))
                .thenReturn(new RouteDecision(primary, prov, RouteStrategy.ROUND_ROBIN, "", java.util.List.of(fallback)));
        when(catalog.requireProvider(10L)).thenReturn(prov);
        when(catalog.decryptKey(prov)).thenReturn("key");

        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(1L, null, null, null, null));
        var stream = client.openChat("sys", "user");
        assertThat(stream).isNotNull();
        ModelRouteContextHolder.clear();
        // 首选 503 → 切换 fallback 200，共 2 次请求
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("401 不重试（配置错误）")
    void noRetryOnAuth() throws Exception {
        startServer(n -> 401);
        Provider prov = new Provider(10L, "p", "OPENAI_COMPAT", baseUrl, "cipher", true, 1L, null, null);
        Profile p = profile(1L);
        when(router.route(null, 1L, null, CallRequirement.chat(true, false, false)))
                .thenReturn(new RouteDecision(p, prov, RouteStrategy.ROUND_ROBIN, ""));
        when(catalog.requireProvider(10L)).thenReturn(prov);
        when(catalog.decryptKey(prov)).thenReturn("key");

        ModelRouteContextHolder.set(new ModelRouteContextHolder.Context(1L, null, null, null, null));
        try {
            client.openChat("sys", "user");
            assertThat(true).as("应抛出异常").isFalse();
        } catch (com.queryforge.shared.error.ApiException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(com.queryforge.shared.error.ErrorCode.LLM_UPSTREAM_ERROR);
        } finally {
            ModelRouteContextHolder.clear();
        }
        // 401 不重试 → 仅 1 次请求
        assertThat(requestCount.get()).isEqualTo(1);
    }
}
