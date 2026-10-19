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
import org.springframework.stereotype.Service;

/**
 * 混合知识检索：关键词通道 + 向量通道 + RRF 融合。
 *
 * 语义（对应 P8 指南 5.2）：
 *  - 权限在数据库级候选筛选阶段生效（owner 或共享授权），不先取全库再隐藏。
 *  - 关键词为稳定基线；向量真实嵌入 + 余弦相似度；混合用可解释的 RRF 融合。
 *  - 每条命中含 sourceId/type/version/score/来源方式，可回溯。
 */
@Service
public class HybridKnowledgeRetriever {

    private static final int MAX_HITS = 5;
    private static final int SNIPPET_CHARS = 200;
    private static final double RRF_K = 60.0;

    private final EmbeddingClient embeddingClient;
    private final KnowledgeIndexRepository indexRepository;

    public HybridKnowledgeRetriever(EmbeddingClient embeddingClient, KnowledgeIndexRepository indexRepository) {
        this.embeddingClient = embeddingClient;
        this.indexRepository = indexRepository;
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
                                            Long userId, Long workspaceId, String query, Mode mode) {
        return switch (mode) {
            case KEYWORD -> new KnowledgeSearchResponse(keywordHits, "KEYWORD");
            case VECTOR -> vectorOnly(userId, workspaceId, query);
            case HYBRID -> hybrid(keywordHits, userId, workspaceId, query);
        };
    }

    /** 仅向量通道：嵌入查询 + 余弦相似度 Top-K。 */
    private KnowledgeSearchResponse vectorOnly(Long userId, Long workspaceId, String query) {
        List<Double> queryVector = embeddingClient.embed(query);
        List<IndexedChunk> chunks = indexRepository.activeChunks(userId, workspaceId);
        List<KnowledgeSearchResponse.Hit> hits = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            double similarity = cosine(queryVector, chunk.embedding());
            hits.add(new KnowledgeSearchResponse.Hit(
                    chunk.knowledgeId(), KnowledgeType.GLOSSARY, chunk.contentSummary() == null ? "" : chunk.contentSummary(),
                    0, similarity, chunk.contentSummary() == null ? "" : snippet(chunk.contentSummary())));
        }
        hits.sort(Comparator.comparingDouble(KnowledgeSearchResponse.Hit::score).reversed());
        return new KnowledgeSearchResponse(top(hits), "VECTOR");
    }

    /** 混合融合：RRF 合并关键词与向量排序。 */
    private KnowledgeSearchResponse hybrid(List<KnowledgeSearchResponse.Hit> keywordHits,
                                           Long userId, Long workspaceId, String query) {
        List<Double> queryVector = embeddingClient.embed(query);
        List<IndexedChunk> chunks = indexRepository.activeChunks(userId, workspaceId);

        // 关键词排名：按已有得分排序的 rank
        Map<Long, Double> keywordRank = new LinkedHashMap<>();
        List<KnowledgeSearchResponse.Hit> keywordSorted = new ArrayList<>(keywordHits);
        keywordSorted.sort(Comparator.comparingDouble(KnowledgeSearchResponse.Hit::score).reversed());
        for (int i = 0; i < keywordSorted.size(); i++) {
            keywordRank.put(keywordSorted.get(i).id(), (double) (i + 1));
        }

        // 向量排名
        List<IndexedChunk> vectorSorted = new ArrayList<>(chunks);
        Map<Long, Double> vectorSim = new LinkedHashMap<>();
        for (IndexedChunk chunk : chunks) {
            vectorSim.put(chunk.knowledgeId(), cosine(queryVector, chunk.embedding()));
        }
        vectorSorted.sort(Comparator.comparingDouble((IndexedChunk c) -> vectorSim.getOrDefault(c.knowledgeId(), 0.0))
                .reversed());
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
        for (IndexedChunk chunk : chunks) {
            meta.putIfAbsent(chunk.knowledgeId(), new KnowledgeSearchResponse.Hit(
                    chunk.knowledgeId(), KnowledgeType.GLOSSARY,
                    chunk.contentSummary() == null ? "" : chunk.contentSummary(), 0, 0.0,
                    chunk.contentSummary() == null ? "" : snippet(chunk.contentSummary())));
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

    /** 重建某知识条目的索引（发布新代，旧代 SUPERSEDED）。 */
    public int indexKnowledge(Long knowledgeId, Long ownerId, Long workspaceId, String title, String content) {
        int generation = indexRepository.currentGeneration(knowledgeId) + 1;
        indexRepository.supersede(knowledgeId);
        // 单 chunk：title + content 摘要嵌入
        String text = (title + "：" + content);
        List<Double> vector = embeddingClient.embed(text);
        indexRepository.insertChunk(knowledgeId, generation, 0, ownerId, workspaceId,
                "qwen3.7-text-embedding-flash", vector.size(), vector, snippet(title));
        return generation;
    }

    public boolean indexReady(Long userId) {
        return indexRepository.activeCount(userId) > 0;
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
