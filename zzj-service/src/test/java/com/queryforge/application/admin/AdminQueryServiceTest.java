package com.queryforge.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.queryforge.application.model.AdminGuard;
import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.domain.identity.UserEntity;
import com.queryforge.infrastructure.persistence.UserRepository;
import com.queryforge.infrastructure.identity.SaTokenIdentityService;
import com.queryforge.infrastructure.persistence.ModelRepository;
import com.queryforge.shared.error.ApiException;
import com.zzj.common.admin.AdminDashboardSummary;
import com.zzj.common.admin.AdminUserPage;
import com.zzj.common.admin.AdminUserQuery;

/** 管理查询应用服务：判权依据持久化角色，DTO 不含凭据，统计只来自真实计数查询。 */
class AdminQueryServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SaTokenIdentityService identityService = mock(SaTokenIdentityService.class);
    private final ModelRepository modelRepository = mock(ModelRepository.class);
    private final AdminQueryService service = new AdminQueryService(users, jdbcTemplate, new AdminGuard(), identityService, modelRepository);

    @Test
    void requirePlatformAdmin_shouldRejectUserRoleFromPersistence() {
        when(users.findPrincipalById(2L)).thenReturn(Optional.of(
                new AppUserPrincipal(2L, "u@example.com", "Uma", null, "USER")));

        assertThatThrownBy(() -> service.requirePlatformAdmin(2L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requirePlatformAdmin_shouldRejectUnknownPrincipal() {
        when(users.findPrincipalById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requirePlatformAdmin(99L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void listUsers_shouldMapEntitiesWithoutCredentials() {
        UserEntity entity = new UserEntity();
        entity.setId(5L);
        entity.setEmail("a@example.com");
        entity.setDisplayName("Alice");
        entity.setRole("ADMIN");
        entity.setCreatedAt(LocalDateTime.of(2026, 10, 22, 9, 30, 15));
        when(users.countByKeyword("")).thenReturn(1L);
        when(users.pageByKeyword("", 1, 20)).thenReturn(List.of(entity));

        AdminUserPage page = service.listUsers(new AdminUserQuery(null, 1, 20));

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getItems().get(0).getCreatedAt()).isEqualTo("2026-10-22 09:30:15");
        assertThat(page.getItems().get(0).getEmail()).isEqualTo("a@example.com");
    }

    @Test
    void dashboard_shouldAggregateRealCountQueries() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);

        AdminDashboardSummary summary = service.dashboard();

        assertThat(summary.getUserTotal()).isEqualTo(7L);
        assertThat(summary.getAdminTotal()).isEqualTo(7L);
        assertThat(summary.getActiveUsers24h()).isEqualTo(7L);
        assertThat(summary.getGeneratedAt()).isNotBlank();
    }
}
