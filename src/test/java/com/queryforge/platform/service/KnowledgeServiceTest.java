package com.queryforge.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.platform.dto.KnowledgeEntryRequest;
import com.queryforge.platform.dto.KnowledgeEntryResponse;
import com.queryforge.platform.dto.KnowledgeSearchResponse;
import com.queryforge.platform.entity.ConnectionConfig;
import com.queryforge.platform.entity.KnowledgeEntry;
import com.queryforge.platform.entity.KnowledgeType;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import com.queryforge.platform.repository.ConnectionRepository;
import com.queryforge.platform.repository.KnowledgeRepository;
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
@DisplayName("知识条目与检索")
class KnowledgeServiceTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 13, 0);

    @Mock
    private KnowledgeRepository repository;

    @Mock
    private ConnectionRepository connectionRepository;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(repository, connectionRepository);
    }

    @Test
    @DisplayName("创建全局术语并返回持久化结果")
    void create_shouldPersistGlobalEntry() {
        when(repository.insert(USER_ID, null, KnowledgeType.GLOSSARY, "有效订单", "已支付且未取消的订单"))
                .thenReturn(7L);
        when(repository.findOwned(7L, USER_ID))
                .thenReturn(Optional.of(entry(7L, null, KnowledgeType.GLOSSARY, "有效订单", "已支付且未取消的订单", 1)));

        KnowledgeEntryResponse created = service.create(USER_ID,
                new KnowledgeEntryRequest(KnowledgeType.GLOSSARY, "有效订单", "已支付且未取消的订单", null));

        assertThat(created.id()).isEqualTo(7L);
        assertThat(created.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("作用域连接非本人所有时拒绝创建")
    void create_shouldRejectForeignConnectionScope() {
        when(connectionRepository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ID,
                new KnowledgeEntryRequest(KnowledgeType.GLOSSARY, "有效订单", "定义", CONNECTION_ID)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_NOT_FOUND));
    }

    @Test
    @DisplayName("检索只返回本人且作用域匹配的条目，标题命中得分更高")
    void search_shouldFilterScopeAndScore() {
        when(connectionRepository.findOwned(CONNECTION_ID, USER_ID)).thenReturn(Optional.of(new ConnectionConfig(
                CONNECTION_ID, USER_ID, "A 商店库", "mysql", "127.0.0.1", 3306,
                "sql_demo_a", "demo_a_reader", "cipher", NOW, NOW)));
        when(repository.searchCandidates(USER_ID, CONNECTION_ID)).thenReturn(List.of(
                entry(1L, null, KnowledgeType.GLOSSARY, "有效订单", "已支付且未取消的订单", 1),
                entry(2L, CONNECTION_ID, KnowledgeType.VERIFIED_SQL_EXAMPLE, "每月订单统计", "SELECT ...", 2),
                entry(3L, null, KnowledgeType.GLOSSARY, "库存周转", "与订单无关", 1)));

        KnowledgeSearchResponse result = service.search(USER_ID, CONNECTION_ID, "有效订单");

        assertThat(result.retrievalMode()).isEqualTo("KEYWORD_DEGRADED");
        assertThat(result.items()).extracting(KnowledgeSearchResponse.Hit::id).contains(1L);
        assertThat(result.items().get(0).id()).isEqualTo(1L);
        assertThat(result.items().get(0).score()).isGreaterThanOrEqualTo(0.9);
        // 2-gram 扩展可能让无关条目低分进入候选，但必须排在精确命中之后。
        assertThat(result.items().stream().mapToDouble(KnowledgeSearchResponse.Hit::score).toArray())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("编辑条目版本递增")
    void update_shouldIncrementVersion() {
        when(repository.findOwned(7L, USER_ID))
                .thenReturn(Optional.of(entry(7L, null, KnowledgeType.GLOSSARY, "有效订单", "旧定义", 1)))
                .thenReturn(Optional.of(entry(7L, null, KnowledgeType.GLOSSARY, "有效订单", "新定义", 2)));
        when(repository.update(7L, USER_ID, null, "有效订单", "新定义", 2)).thenReturn(1);

        KnowledgeEntryResponse updated = service.update(USER_ID, 7L,
                new KnowledgeEntryRequest(KnowledgeType.GLOSSARY, "有效订单", "新定义", null));

        assertThat(updated.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("删除他人的条目返回 404")
    void delete_shouldRejectForeignEntry() {
        when(repository.delete(7L, USER_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(USER_ID, 7L))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.HISTORY_NOT_FOUND));
        verify(repository).delete(7L, USER_ID);
    }

    private static KnowledgeEntry entry(Long id, Long connectionId, KnowledgeType type,
                                        String title, String content, int version) {
        return new KnowledgeEntry(
                id, USER_ID, connectionId, type, title, content, "ACTIVE", version, NOW, NOW);
    }
}
