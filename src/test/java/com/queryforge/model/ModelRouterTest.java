package com.queryforge.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.queryforge.model.ModelEntities.Profile;
import com.queryforge.model.ModelRouter.CallRequirement;
import com.queryforge.model.ModelRouter.RouteDecision;
import com.queryforge.shared.error.ApiException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("模型路由与能力过滤")
class ModelRouterTest {

    @Mock
    private ModelRepository repository;
    @Mock
    private ModelCatalogService catalog;

    private ModelHealthRegistry health;
    private ModelRouter router;

    private static Profile profile(long id, long providerId, String type, boolean streaming,
                                   boolean toolCalls, boolean json) {
        return new Profile(id, providerId, "model-" + id, type, streaming, toolCalls, json,
                8192, 2048, null, "ACTIVE", true, null, null);
    }

    @BeforeEach
    void setUp() {
        health = new ModelHealthRegistry();
        router = new ModelRouter(repository, catalog, health);
    }

    @Test
    @DisplayName("类型不匹配被排除")
    void excludesTypeMismatch() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L, "EMBEDDING", true, true, true)));
        assertThatThrownBy(() -> router.route(null, 1L, null, CallRequirement.chat(true, false, false)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("需要工具调用但模型不支持时被排除")
    void excludesNoToolCalls() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L, "CHAT", true, false, false)));
        assertThatThrownBy(() -> router.route(null, 1L, null, CallRequirement.chat(true, true, false)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(com.queryforge.shared.error.ErrorCode.MODEL_UNAVAILABLE);
    }

    @Test
    @DisplayName("未授权模型被拒绝")
    void rejectsUnauthorized() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L, "CHAT", true, true, true)));
        when(repository.isGranted(1L, "USER", 1L)).thenReturn(false);
        assertThatThrownBy(() -> router.route(null, 1L, null, CallRequirement.chat(true, true, true)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("授权且兼容的模型被选中")
    void selectsAuthorizedCompatible() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L, "CHAT", true, true, true)));
        when(repository.isGranted(1L, "USER", 1L)).thenReturn(true);
        when(catalog.requireProvider(10L)).thenReturn(new ModelEntities.Provider(
                10L, "p", "OPENAI_COMPAT", "https://x.com/v1", "cipher", true, 1L, null, null));
        RouteDecision decision = router.route(null, 1L, null, CallRequirement.chat(true, true, true));
        assertThat(decision.profile().id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("熔断 OPEN 状态排除模型")
    void excludesCircuitOpen() {
        when(repository.listProfiles()).thenReturn(List.of(profile(1L, 10L, "CHAT", true, true, true)));
        when(repository.isGranted(1L, "USER", 1L)).thenReturn(true);
        when(catalog.requireProvider(10L)).thenReturn(new ModelEntities.Provider(
                10L, "p", "OPENAI_COMPAT", "https://x.com/v1", "cipher", true, 1L, null, null));
        for (int i = 0; i < 5; i++) {
            health.recordFailure(1L, 100);
        }
        assertThatThrownBy(() -> router.route(null, 1L, null, CallRequirement.chat(true, true, true)))
                .isInstanceOf(ApiException.class);
    }
}
