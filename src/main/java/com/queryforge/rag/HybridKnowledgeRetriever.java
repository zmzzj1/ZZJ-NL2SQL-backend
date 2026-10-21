package com.queryforge.rag;

import com.queryforge.knowledge.KnowledgeEntry;
import com.queryforge.knowledge.KnowledgeSearchResponse;
import com.queryforge.knowledge.KnowledgeType;
import com.queryforge.rag.KnowledgeIndexRepository.IndexedChunk;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 混合知识检索：关键词通道 + 向量通道 + RRF 融合。
 *
 * 语义（对应 P8 指南 5.2）：
 *  - 权限在数据库级候选筛选阶段生效（owner 或共享授权），不先取全库再隐藏。
 *  - 关键词为稳定基线；向量真实嵌入 + 余弦相似度；混合用可解释的 RRF 融合。
 *  - 每条命中含 sourceId/type/version/score/来源方式，可回溯。
 *
 * P12 S3：知识向量在写入 MySQL 索引（既有）之外，同时投影到 PostgreSQL pgvector
 * （{@link PgVectorStore}），删除时同步失效；PG 不可用时向量写入 fail-soft，不影响主保存与关键词检索。
 */
@Service
public class HybridKnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeRetriever.class);
    private static final int MAX_HITS = 5;
    private static final int SNIPPET_CHARS = 200;
    private static final double RRF_K = 60.0;

    private final EmbeddingClient embeddingClient;
    private final KnowledgeIndexRepository indexRepository;
    private final PgVectorStore pgVectorStore;

    public HybridKnowledgeRetriever(EmbeddingClient embeddingClient, KnowledgeIndexRepository indexRepository,
                                    PgVectorStore pgVectorStore) {
        this.embeddingClient = embeddingClient;
        this.indexRepository = indexRepository;
        this.pgVectorStore = pgVectorStore;
    }

    /** 检索模式。 */
    public enum Mode {
        KEYWORD, VECTOR, HYBRID
    }

    /**
     * 混合检索入口。
     *
     * @param keywordHits 关键词通道结果（来自 P5 KnowledgeService，已权限过滤）
     * @param userId      当前用户
     * @param workspaceId 当前空间（可空）
     * @param query       问题
     * @param mode        检索模式
     */
    public KnowledgeSearchResponse retrieve(List<KnowledgeSearchResponse.Hit> keywordHits,
                                            Long userId, Long workspaceId, Long connectionId, String query, Mode mode) {
        return switch (mode) {
            case KEYWORD -> new KnowledgeSearchResponse(keywordHits, "KEYWORD");
            case VECTOR -> vectorOnly(userId, workspaceId, connectionId, query);
            case HYBRID -> hybrid(keywordHits, userId, workspaceId, connectionId, query);
        };
    }

    /** 向量命中（knowledgeId + 类型 + 摘要 + 相似度）。 */
    private record VectorMatch(long knowledgeId, KnowledgeType type, String summary, double similarity) {
    }

    /**
     * 向量候选：pgvector 余弦相似度（本人知识，owner+connection+type 过滤）+ MySQL 空间共享知识。
     * 按 knowledgeId 去重合并（PG 优先、MySQL 兜底），保证 PG 缺失部分不丢数据；PG 不可用时整体回退 MySQL。
     */
    private List<VectorMatch> vectorMatches(Long userId, Long workspaceId, Long connectionId, List<Double> queryVector) {
        Map<Long, VectorMatch> byId = new LinkedHashMap<>();
        try {
            for (String vt : List.of(PgVectorStore.TYPE_GLOSSARY, PgVectorStore.TYPE_QUESTION_KNOWLEDGE)) {
                for (PgVectorStore.Hit h : pgVectorStore.similaritySearch(vt, userId, connectionId, queryVector, MAX_HITS)) {
                    byId.put(h.sourceId(), new VectorMatch(h.sourceId(), fromVectorType(vt),
                            h.contentSummary() == null ? "" : h.contentSummary(), 1.0 - h.cosineDistance()));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("pg_vector_search_failed, fallback to mysql", ex);
        }
        // MySQL：本人（PG 漏写时兜底）+ 空间共享知识（PG 无共享投影）
        for (IndexedChunk c : indexRepository.activeChunks(userId, workspaceId)) {
            byId.putIfAbsent(c.knowledgeId(), new VectorMatch(c.knowledgeId(), KnowledgeType.GLOSSARY,
                    c.contentSummary() == null ? "" : c.contentSummary(), cosine(queryVector, c.embedding())));
        }
        return new ArrayList<>(byId.values());
    }

    private static KnowledgeType fromVectorType(String vt) {
        return PgVectorStore.TYPE_QUESTION_KNOWLEDGE.equals(vt)
                ? KnowledgeType.VERIFIED_SQL_EXAMPLE : KnowledgeType.GLOSSARY;
    }

    /** 仅向量通道：嵌入查询 + 相似度 Top-K。 */
    private KnowledgeSearchResponse vectorOnly(Long userId, Long workspaceId, Long connectionId, String query) {
        List<Double> queryVector = embeddingClient.embed(query);
        List<VectorMatch> matches = vectorMatches(userId, workspaceId, connectionId, queryVector);
        matches.sort(Comparator.comparingDouble(VectorMatch::similarity).reversed());
        List<KnowledgeSearchResponse.Hit> hits = matches.stream()
                .map(m -> new KnowledgeSearchResponse.Hit(m.knowledgeId(), m.type(), m.summary(),
                        0, m.similarity(), snippet(m.summary())))
                .toList();
        return new KnowledgeSearchResponse(top(hits), "VECTOR");
    }

    /** 混合融合：RRF 合并关键词与向量排序。 */
    private KnowledgeSearchResponse hybrid(List<KnowledgeSearchResponse.Hit> keywordHits,
                                           Long userId, Long workspaceId, Long connectionId, String query) {
        List<Double> queryVector = embeddingClient.embed(query);
        List<VectorMatch> matches = vectorMatches(userId, workspaceId, connectionId, queryVector);

        // 关键词排名：按已有得分排序的 rank
        Map<Long, Double> keywordRank = new LinkedHashMap<>();
        List<KnowledgeSearchResponse.Hit> keywordSorted = new ArrayList<>(keywordHits);
        keywordSorted.sort(Comparator.comparingDouble(KnowledgeSearchResponse.Hit::score).reversed());
        for (int i = 0; i < keywordSorted.size(); i++) {
            keywordRank.put(keywordSorted.get(i).id(), (double) (i + 1));
        }

        // 向量排名（按相似度降序）
        List<VectorMatch> vectorSorted = new ArrayList<>(matches);
        vectorSorted.sort(Comparator.comparingDouble(VectorMatch::similarity).reversed());
        Map<Long, Double> vectorRank = new LinkedHashMap<>();
        for (int i = 0; i < vectorSorted.size(); i++) {
            vectorRank.put(vectorSorted.get(i).knowledgeId(), (double) (i + 1));
        }

        // RRF 融合
        Map<Long, Double> fused = new LinkedHashMap<>();
        for (Long id : keywordRank.keySet()) {
            fused.put(id, fused.getOrDefault(id, 0.0) + 1.0 / (RRF_K + keywordRank.get(id)));
        }
        for (Long id : vectorRank.keySet()) {
            fused.put(id, fused.getOrDefault(id, 0.0) + 1.0 / (RRF_K + vectorRank.get(id)));
        }

        // 组装命中（保留标题与摘要，优先从关键词命中取元数据）
        Map<Long, KnowledgeSearchResponse.Hit> meta = new LinkedHashMap<>();
        for (KnowledgeSearchResponse.Hit hit : keywordHits) {
            meta.putIfAbsent(hit.id(), hit);
        }
        for (VectorMatch m : matches) {
            meta.putIfAbsent(m.knowledgeId(), new KnowledgeSearchResponse.Hit(
                    m.knowledgeId(), m.type(), m.summary(), 0, 0.0, snippet(m.summary())));
        }

        List<KnowledgeSearchResponse.Hit> hits = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : fused.entrySet()) {
            KnowledgeSearchResponse.Hit base = meta.get(entry.getKey());
            if (base != null) {
                hits.add(new KnowledgeSearchResponse.Hit(
                        base.id(), base.sourceType(), base.title(), base.version(),
                        entry.getValue(), base.snippet()));
            }
        }
        hits.sort(Comparator.comparingDouble(KnowledgeSearchResponse.Hit::score).reversed());
        return new KnowledgeSearchResponse(top(hits), "HYBRID");
    }

    /** 重建某知识条目的索引（发布新代，旧代 SUPERSEDED），并投影到 pgvector。 */
    public int indexKnowledge(Long knowledgeId, Long ownerId, Long workspaceId, Long connectionId,
                              KnowledgeType type, String title, String content) {
        int generation = indexRepository.currentGeneration(knowledgeId) + 1;
        indexRepository.supersede(knowledgeId);
        // 单 chunk：title + content 摘要嵌入
        String text = (title + "：" + content);
        List<Double> vector = embeddingClient.embed(text);
        indexRepository.insertChunk(knowledgeId, generation, 0, ownerId, workspaceId,
                "qwen3.7-text-embedding-flash", vector.size(), vector, snippet(title));
        // P12 S3：双写 pgvector（先失效旧向量再写新，保证 update 换 type/connection 不残留）
        try {
            pgVectorStore.deactivate(PgVectorStore.TYPE_GLOSSARY, knowledgeId);
            pgVectorStore.deactivate(PgVectorStore.TYPE_QUESTION_KNOWLEDGE, knowledgeId);
            pgVectorStore.add(toVectorType(type), knowledgeId, ownerId, connectionId, workspaceId,
                    vector, snippet(title));
        } catch (RuntimeException ex) {
            log.warn("pg_vector_index_failed knowledgeId={} type={}", knowledgeId, type, ex);
        }
        return generation;
    }

    /** 知识删除/更新时同步失效：MySQL 旧代 SUPERSEDED + pgvector 软删除。 */
    public void deindex(Long knowledgeId) {
        indexRepository.supersede(knowledgeId);
        try {
            pgVectorStore.deactivate(PgVectorStore.TYPE_GLOSSARY, knowledgeId);
            pgVectorStore.deactivate(PgVectorStore.TYPE_QUESTION_KNOWLEDGE, knowledgeId);
        } catch (RuntimeException ex) {
            log.warn("pg_vector_deindex_failed knowledgeId={}", knowledgeId, ex);
        }
    }

    public boolean indexReady(Long userId) {
        return indexRepository.activeCount(userId) > 0;
    }

    /** KnowledgeType → pgvector vector_type 映射。 */
    private static String toVectorType(KnowledgeType type) {
        return type == KnowledgeType.GLOSSARY
                ? PgVectorStore.TYPE_GLOSSARY
                : PgVectorStore.TYPE_QUESTION_KNOWLEDGE;
    }

    private static List<KnowledgeSearchResponse.Hit> top(List<KnowledgeSearchResponse.Hit> hits) {
        return hits.size() > MAX_HITS ? hits.subList(0, MAX_HITS) : hits;
    }

    static double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0.0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static String snippet(String value) {
        String content = value == null ? "" : value.trim();
        return content.length() > SNIPPET_CHARS ? content.substring(0, SNIPPET_CHARS) + "…" : content;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim();
    }
}
