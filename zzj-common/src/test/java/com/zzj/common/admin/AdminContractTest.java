package com.zzj.common.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 契约层约束：分页上限、关键词收敛、跨进程可序列化与失败壳语义。 */
class AdminContractTest {

    @Test
    void query_shouldClampPagingToContractLimit() {
        AdminUserQuery query = new AdminUserQuery("  alice ", 0, 5000);

        assertThat(query.getPage()).isEqualTo(1);
        assertThat(query.getSize()).isEqualTo(AdminUserQuery.MAX_SIZE);
        assertThat(query.getKeyword()).isEqualTo("alice");
    }

    @Test
    void query_shouldFallbackToDefaultsForInvalidPaging() {
        AdminUserQuery query = new AdminUserQuery(null, -3, -1);

        assertThat(query.getPage()).isEqualTo(1);
        assertThat(query.getSize()).isEqualTo(AdminUserQuery.DEFAULT_SIZE);
        assertThat(query.getKeyword()).isEmpty();
    }

    @Test
    void query_shouldTruncateOversizedKeyword() {
        AdminUserQuery query = new AdminUserQuery("x".repeat(200), 1, 20);

        assertThat(query.getKeyword()).hasSize(AdminUserQuery.MAX_KEYWORD_LENGTH);
    }

    @Test
    void userSummary_shouldExposeOnlyAccountMetadata() {
        AdminUserSummary summary = new AdminUserSummary(1L, "a@example.com", "Alice", "ADMIN", "2026-10-22 10:00:00");

        String rendered = summary.getId() + summary.getEmail() + summary.getDisplayName()
                + summary.getRole() + summary.getCreatedAt();
        assertThat(rendered).doesNotContain("password").doesNotContain("token").doesNotContain("secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rpcResult_shouldSurviveCrossProcessSerialization() throws Exception {
        AdminUserPage page = new AdminUserPage(
                List.of(new AdminUserSummary(7L, "b@example.com", "Bob", "USER", "2026-10-22 11:00:00")),
                1L, 1, 20);
        AdminRpcResult<AdminUserPage> result = AdminRpcResult.success(page);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(result);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            AdminRpcResult<AdminUserPage> decoded = (AdminRpcResult<AdminUserPage>) in.readObject();
            assertThat(decoded.isOk()).isTrue();
            assertThat(decoded.getData().getTotal()).isEqualTo(1L);
            assertThat(decoded.getData().getItems().get(0).getEmail()).isEqualTo("b@example.com");
        }
    }

    @Test
    void failureResult_shouldCarryErrorCodeWithoutData() {
        AdminRpcResult<AdminDashboardSummary> failure = AdminRpcResult.failure(AdminRpcErrorCode.FORBIDDEN, "仅平台管理员");

        assertThat(failure.isOk()).isFalse();
        assertThat(failure.getErrorCode()).isEqualTo(AdminRpcErrorCode.FORBIDDEN);
        assertThat(failure.getData()).isNull();
    }
}
