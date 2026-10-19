package com.queryforge.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.queryforge.memory.MemoryService.Confidence;
import com.queryforge.memory.MemoryService.MemoryType;
import com.queryforge.memory.MemoryService.Scope;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.workspace.WorkspaceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("长期 Memory 与权限")
class MemoryServiceTest {

    private static final long USER_ID = 1L;
    private static final long WORKSPACE_ID = 100L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 0, 0);

    @Mock
    private MemoryRepository repository;

    @Mock
    private WorkspaceService workspaceService;

    private MemoryService service;

    @BeforeEach
    void setUp() {
        service = new MemoryService(repository, workspaceService);
    }

    @Test
    @DisplayName("个人记忆创建成功，workspaceId 被置空")
    void create_personalMemory_ignoresWorkspace() {
        when(repository.findFingerprint(anyLong(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.insert(eq(USER_ID), any(), eq("FACT"), eq("PERSONAL"), eq("有效订单定义"),
                eq("已支付且未取消"), any(), anyString(), any())).thenReturn(9L);
        when(repository.findOwned(9L, USER_ID))
                .thenReturn(Optional.of(memory(9L, null, "FACT", "PERSONAL", "有效订单定义")));

        LongTermMemory created = service.create(USER_ID, WORKSPACE_ID, MemoryType.FACT, Scope.PERSONAL,
                "有效订单定义", "已支付且未取消", "conv-1", Confidence.VERIFIED, null);

        assertThat(created.id()).isEqualTo(9L);
        assertThat(created.workspaceId()).isNull();
    }

    @Test
    @DisplayName("工作空间记忆需成员资格，非成员被拒")
    void create_workspaceMemory_requiresMembership() {
        when(workspaceService.requireActiveMember(WORKSPACE_ID, USER_ID))
                .thenThrow(new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, org.springframework.http.HttpStatus.NOT_FOUND,
                        "空间不存在"));

        assertThatThrownBy(() -> service.create(USER_ID, WORKSPACE_ID, MemoryType.FACT, Scope.WORKSPACE,
                "共享事实", "内容", null, Confidence.UNVERIFIED, null))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    @Test
    @DisplayName("个人记忆检索只返回本人个人记录，不跨用户")
    void search_personal_returnsOnlyOwnPersonal() {
        when(repository.searchCandidates(USER_ID, null, "PERSONAL"))
                .thenReturn(List.of(memory(1L, null, "FACT", "PERSONAL", "有效订单")));

        List<LongTermMemory> result = service.search(USER_ID, null, Scope.PERSONAL, "有效订单");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).scope()).isEqualTo("PERSONAL");
    }

    private static LongTermMemory memory(Long id, Long workspaceId, String type, String scope, String title) {
        return new LongTermMemory(id, USER_ID, workspaceId, type, scope, title, "内容", "src",
                "VERIFIED", "ACTIVE", null, NOW, NOW);
    }
}
