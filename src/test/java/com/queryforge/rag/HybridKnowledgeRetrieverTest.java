package com.queryforge.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.queryforge.knowledge.KnowledgeSearchResponse;
import com.queryforge.knowledge.KnowledgeType;
import com.queryforge.rag.HybridKnowledgeRetriever.Mode;
import com.queryforge.rag.KnowledgeIndexRepository.IndexedChunk;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("混合检索融合")
class HybridKnowledgeRetrieverTest {

    private static final long USER_ID = 1L;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private KnowledgeIndexRepository indexRepository;

    private HybridKnowledgeRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new HybridKnowledgeRetriever(embeddingClient, indexRepository);
    }

    @Test
    @DisplayName("余弦相似度：相同向量为 1，正交为 0")
    void cosine_shouldComputeSimilarity() {
        assertThat(HybridKnowledgeRetriever.cosine(List.of(1.0, 0.0), List.of(1.0, 0.0))).isEqualTo(1.0);
        assertThat(HybridKnowledgeRetriever.cosine(List.of(1.0, 0.0), List.of(0.0, 1.0))).isEqualTo(0.0);
        assertThat(HybridKnowledgeRetriever.cosine(List.of(1.0, 1.0), List.of(1.0, 1.0)))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(HybridKnowledgeRetriever.cosine(List.of(), List.of(1.0))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("关键词模式直接返回关键词命中")
    void retrieve_keywordMode_returnsKeywordHits() {
        List<KnowledgeSearchResponse.Hit> keywordHits = List.of(
                new KnowledgeSearchResponse.Hit(1L, KnowledgeType.GLOSSARY, "有效订单", 1, 0.9, "已支付订单"));

        KnowledgeSearchResponse result = retriever.retrieve(keywordHits, USER_ID, null, "有效订单", Mode.KEYWORD);

        assertThat(result.retrievalMode()).isEqualTo("KEYWORD");
        assertThat(result.items()).hasSize(1);
    }

    @Test
    @DisplayName("向量模式真实嵌入查询并按相似度排序")
    void retrieve_vectorMode_usesEmbedding() {
        when(embeddingClient.embed("有效订单")).thenReturn(List.of(1.0, 0.0));
        when(indexRepository.activeChunks(USER_ID, null)).thenReturn(List.of(
                chunk(1L, List.of(1.0, 0.0), "有效订单定义"),
                chunk(2L, List.of(0.0, 1.0), "库存定义")));

        KnowledgeSearchResponse result = retriever.retrieve(List.of(), USER_ID, null, "有效订单", Mode.VECTOR);

        assertThat(result.retrievalMode()).isEqualTo("VECTOR");
        assertThat(result.items()).isNotEmpty();
        assertThat(result.items().get(0).id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("混合模式用 RRF 融合关键词与向量")
    void retrieve_hybridMode_fusesRankings() {
        List<KnowledgeSearchResponse.Hit> keywordHits = List.of(
                new KnowledgeSearchResponse.Hit(1L, KnowledgeType.GLOSSARY, "有效订单", 1, 0.9, "已支付订单"));
        when(embeddingClient.embed("有效订单")).thenReturn(List.of(1.0, 0.0));
        when(indexRepository.activeChunks(USER_ID, null)).thenReturn(List.of(
                chunk(1L, List.of(1.0, 0.0), "有效订单定义"),
                chunk(2L, List.of(0.0, 1.0), "库存定义")));

        KnowledgeSearchResponse result = retriever.retrieve(keywordHits, USER_ID, null, "有效订单", Mode.HYBRID);

        assertThat(result.retrievalMode()).isEqualTo("HYBRID");
        assertThat(result.items()).extracting(KnowledgeSearchResponse.Hit::id).contains(1L);
    }

    private static IndexedChunk chunk(Long knowledgeId, List<Double> vector, String summary) {
        return new IndexedChunk(knowledgeId, knowledgeId, 0, USER_ID, null,
                "qwen3.7-text-embedding-flash", vector.size(), vector, summary);
    }
}
