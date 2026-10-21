package com.queryforge.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.memory.MemoryService.Confidence;
import com.queryforge.memory.MemoryService.MemoryType;
import com.queryforge.memory.MemoryService.Scope;
import com.queryforge.rag.EmbeddingClient;
import com.queryforge.rag.PgVectorStore;
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
import org.springframework.http.HttpStatus;

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

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private PgVectorStore pgVectorStore;

    private MemoryService service;

    @BeforeEach
    void setUp() {
        service = new MemoryService(repository, workspaceService, embeddingClient, pgVectorStore);
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
                .thenThrow(new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, HttpStatus.NOT_FOUND,
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
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1));
        when(pgVectorStore.similaritySearch(eq(PgVectorStore.TYPE_MEMORY), eq(USER_ID), isNull(),
                any(), eq(16))).thenReturn(List.of());

        List<LongTermMemory> result = service.search(USER_ID, null, Scope.PERSONAL, "有效订单");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).scope()).isEqualTo("PERSONAL");
    }

    @Test
    @DisplayName("P12 S3：创建后向量投影到 pgvector（MEMORY 类型，个人记忆无 workspace）")
    void create_shouldProjectVectorToPg() {
        when(repository.findFingerprint(anyLong(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.insert(eq(USER_ID), any(), eq("FACT"), eq("PERSONAL"), eq("有效订单定义"),
                eq("已支付且未取消"), any(), anyString(), any())).thenReturn(9L);
        when(repository.findOwned(9L, USER_ID))
                .thenReturn(Optional.of(memory(9L, null, "FACT", "PERSONAL", "有效订单定义")));
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2));

        service.create(USER_ID, null, MemoryType.FACT, Scope.PERSONAL,
                "有效订单定义", "已支付且未取消", "conv-1", Confidence.VERIFIED, null);

        verify(pgVectorStore).add(eq(PgVectorStore.TYPE_MEMORY), eq(9L), eq(USER_ID),
                isNull(), isNull(), eq(List.of(0.1, 0.2)), eq("有效订单定义"));
    }

    @Test
    @DisplayName("P12 S3：工作空间记忆的向量投影带 workspaceId，connectionId 为空")
    void create_workspaceMemory_projectsVectorWithWorkspace() {
        when(repository.findFingerprint(anyLong(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.insert(eq(USER_ID), eq(WORKSPACE_ID), eq("FACT"), eq("WORKSPACE"), eq("空间口径"),
                eq("共享内容"), any(), anyString(), any())).thenReturn(9L);
        when(repository.findOwned(9L, USER_ID))
                .thenReturn(Optional.of(memory(9L, WORKSPACE_ID, "FACT", "WORKSPACE", "空间口径")));
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2));

        service.create(USER_ID, WORKSPACE_ID, MemoryType.FACT, Scope.WORKSPACE,
                "空间口径", "共享内容", null, Confidence.UNVERIFIED, null);

        verify(pgVectorStore).add(eq(PgVectorStore.TYPE_MEMORY), eq(9L), eq(USER_ID),
                isNull(), eq(WORKSPACE_ID), eq(List.of(0.1, 0.2)), eq("空间口径"));
    }

    @Test
    @DisplayName("P12 S3：Embedding 不可用时创建仍然成功（fail-soft，不写向量）")    void create_shouldStayAvailable_whenEmbeddingUnavailable() {
        when(repository.findFingerprint(anyLong(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.insert(eq(USER_ID), any(), eq("FACT"), eq("PERSONAL"), eq("有效订单定义"),
                eq("已支付且未取消"), any(), anyString(), any())).thenReturn(9L);
        when(repository.findOwned(9L, USER_ID))
                .thenReturn(Optional.of(memory(9L, null, "FACT", "PERSONAL", "有效订单定义")));
        when(embeddingClient.embed(anyString())).thenThrow(new ApiException(
                ErrorCode.EMBEDDING_UNAVAILABLE, HttpStatus.BAD_GATEWAY, "不可用"));

        LongTermMemory created = service.create(USER_ID, null, MemoryType.FACT, Scope.PERSONAL,
                "有效订单定义", "已支付且未取消", "conv-1", Confidence.VERIFIED, null);

        assertThat(created.id()).isEqualTo(9L);
        verify(pgVectorStore, never()).add(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("P12 S3：更新后重新投影向量（先失效旧向量）")
    void update_shouldReindexVector() {
        when(repository.findOwned(9L, USER_ID))
                .thenReturn(Optional.of(memory(9L, null, "FACT", "PERSONAL", "旧标题")));
        when(repository.update(eq(9L), eq(USER_ID), anyString(), anyString(), anyString())).thenReturn(1);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.3));

        service.update(USER_ID, 9L, "新标题", "新内容", Confidence.VERIFIED);

        verify(pgVectorStore).deactivate(PgVectorStore.TYPE_MEMORY, 9L);
        verify(pgVectorStore).add(eq(PgVectorStore.TYPE_MEMORY), eq(9L), eq(USER_ID),
                isNull(), isNull(), eq(List.of(0.3)), eq("新标题"));
    }

    @Test
    @DisplayName("P12 S3：停用后向量同步失效，过期内容不再注入")
    void disable_shouldDeactivateVector() {
        when(repository.findOwned(9L, USER_ID))
                .thenReturn(Optional.of(memory(9L, null, "FACT", "PERSONAL", "待停用")));
        when(repository.disable(9L, USER_ID)).thenReturn(1);

        service.disable(USER_ID, 9L);

        verify(pgVectorStore).deactivate(PgVectorStore.TYPE_MEMORY, 9L);
    }

    @Test
    @DisplayName("P12 S3：删除后向量同步失效")
    void delete_shouldDeactivateVector() {
        when(repository.findOwned(9L, USER_ID))
                .thenReturn(Optional.of(memory(9L, null, "FACT", "PERSONAL", "待删除")));
        when(repository.delete(9L, USER_ID)).thenReturn(1);

        service.delete(USER_ID, 9L);

        verify(pgVectorStore).deactivate(PgVectorStore.TYPE_MEMORY, 9L);
    }

    @Test
    @DisplayName("P12 S3：向量命中只对授权候选集内记录生效，PG 中的他人记录被排除")
    void search_shouldMergeVectorHitsForAuthorizedCandidatesOnly() {
        when(repository.searchCandidates(USER_ID, null, "PERSONAL"))
                .thenReturn(List.of(memory(1L, null, "FACT", "PERSONAL", "有效订单")));
        when(embeddingClient.embed("完全无关的查询词")).thenReturn(List.of(1.0, 0.0));
        when(pgVectorStore.similaritySearch(eq(PgVectorStore.TYPE_MEMORY), eq(USER_ID), isNull(),
                any(), eq(16)))
                .thenReturn(List.of(
                        new PgVectorStore.Hit(PgVectorStore.TYPE_MEMORY, 1L, USER_ID, null, 0.05f, "摘要"),
                        new PgVectorStore.Hit(PgVectorStore.TYPE_MEMORY, 99L, USER_ID, null, 0.01f, "他人记忆")));

        List<LongTermMemory> result = service.search(USER_ID, null, Scope.PERSONAL, "完全无关的查询词");

        assertThat(result).extracting(LongTermMemory::id).containsExactly(1L);
    }

    @Test
    @DisplayName("P12 S3：Embedding 故障时检索退化为纯关键词，不报错")
    void search_shouldFallBackToKeyword_whenEmbeddingFails() {
        when(repository.searchCandidates(USER_ID, null, "PERSONAL"))
                .thenReturn(List.of(memory(1L, null, "FACT", "PERSONAL", "有效订单")));
        when(embeddingClient.embed(anyString())).thenThrow(new ApiException(
                ErrorCode.EMBEDDING_UNAVAILABLE, HttpStatus.BAD_GATEWAY, "不可用"));

        List<LongTermMemory> result = service.search(USER_ID, null, Scope.PERSONAL, "有效订单");

        assertThat(result).hasSize(1);
    }

    private static LongTermMemory memory(Long id, Long workspaceId, String type, String scope, String title) {
        return new LongTermMemory(id, USER_ID, workspaceId, type, scope, title, "内容", "src",
                "VERIFIED", "ACTIVE", null, NOW, NOW);
    }
}
