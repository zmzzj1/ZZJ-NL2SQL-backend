package com.queryforge.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.queryforge.domain.model.ModelEntities.RoutePolicyVersion;
import com.queryforge.shared.error.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.queryforge.infrastructure.persistence.ModelRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("路由策略版本化")
class RoutePolicyServiceTest {

    @Mock
    private ModelRepository repository;

    private RoutePolicyService service;

    private static RoutePolicyVersion version(long id, long policyId, int versionNo, String status) {
        return new RoutePolicyVersion(id, policyId, versionNo, "ROUND_ROBIN", "[1]", "[]", null, 0,
                status, LocalDateTime.now(), LocalDateTime.now());
    }

    @BeforeEach
    void setUp() {
        service = new RoutePolicyService(repository);
    }

    @Test
    @DisplayName("候选为空被拒绝")
    void rejectsEmptyCandidates() {
        assertThatThrownBy(() -> service.saveDraft(1L, 1L, "ROUND_ROBIN", List.of(), List.of(), null, 0))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("未知策略被拒绝")
    void rejectsUnknownStrategy() {
        assertThatThrownBy(() -> service.saveDraft(1L, 1L, "MAGIC", List.of(1L), List.of(), null, 0))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("发布生成新的不可变版本")
    void publishCreatesNewVersion() {
        RoutePolicyVersion draft = version(10L, 1L, 1, "DRAFT");
        when(repository.findPolicyVersion(10L)).thenReturn(Optional.of(draft));
        when(repository.maxPolicyVersionNo(1L)).thenReturn(1);
        when(repository.publishVersion(10L, 2, "ROUND_ROBIN", "[1]", "[]", null, 0)).thenReturn(20L);
        RoutePolicyVersion published = version(20L, 1L, 2, "PUBLISHED");
        when(repository.findPolicyVersion(20L)).thenReturn(Optional.of(published));

        RoutePolicyVersion result = service.publish(1L, 10L);
        assertThat(result.versionNo()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("回滚保留历史并生成新版本")
    void rollbackPreservesHistory() {
        RoutePolicyVersion base = version(5L, 1L, 1, "PUBLISHED");
        when(repository.findPolicyVersion(5L)).thenReturn(Optional.of(base));
        when(repository.maxPolicyVersionNo(1L)).thenReturn(3);
        when(repository.publishVersion(5L, 4, "ROUND_ROBIN", "[1]", "[]", null, 0)).thenReturn(40L);
        RoutePolicyVersion rolled = version(40L, 1L, 4, "PUBLISHED");
        when(repository.findPolicyVersion(40L)).thenReturn(Optional.of(rolled));

        RoutePolicyVersion result = service.rollback(1L, 5L);
        assertThat(result.versionNo()).isEqualTo(4);
    }
}
