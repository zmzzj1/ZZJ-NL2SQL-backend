package com.queryforge.application.knowledge;

import com.queryforge.domain.knowledge.KnowledgeEntryRequest;
import com.queryforge.domain.knowledge.KnowledgeEntryResponse;
import com.queryforge.domain.knowledge.KnowledgeSearchResponse;
import com.queryforge.domain.knowledge.KnowledgeEntry;
import com.queryforge.domain.knowledge.KnowledgeType;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.infrastructure.persistence.ConnectionRepository;
import com.queryforge.infrastructure.persistence.KnowledgeRepository;
import com.queryforge.application.rag.HybridKnowledgeRetriever;
import com.queryforge.application.workspace.WorkspaceAccessPolicy;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 业务术语与审核 SQL 示例的维护与检索。
 * 关键词检索为稳定基线；P12 S3 起在保存/更新/删除时同步维护向量索引（MySQL + pgvector）。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final int MAX_HITS = 5;
    private static final int SNIPPET_CHARS = 200;
    private static final String RETRIEVAL_MODE = "KEYWORD_DEGRADED";

    private final KnowledgeRepository repository;
    private final ConnectionRepository connectionRepository;
    private final WorkspaceAccessPolicy workspaceAccessPolicy;
    private final HybridKnowledgeRetriever hybridRetriever;

    public KnowledgeService(KnowledgeRepository repository, ConnectionRepository connectionRepository,
                            WorkspaceAccessPolicy workspaceAccessPolicy, HybridKnowledgeRetriever hybridRetriever) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.workspaceAccessPolicy = workspaceAccessPolicy;
        this.hybridRetriever = hybridRetriever;
    }

    public KnowledgeEntryResponse create(Long userId, KnowledgeEntryRequest request) {
        validateScope(userId, request.connectionId());
        Long id = repository.insert(
                userId, request.connectionId(), request.sourceType(), request.title().trim(), request.content().trim());
        KnowledgeEntry saved = repository.findOwned(id, userId).orElseThrow(KnowledgeService::notFound);
        indexSafely(saved);
        return toResponse(saved);
    }

    public KnowledgeEntryResponse update(Long userId, Long id, KnowledgeEntryRequest request) {
        KnowledgeEntry existing = requireOwned(id, userId);
        validateScope(userId, request.connectionId());
        int newVersion = existing.version() + 1;
        if (repository.update(id, userId, request.connectionId(), request.title().trim(),
                request.content().trim(), newVersion) == 0) {
            throw notFound();
        }
        KnowledgeEntry updated = requireOwned(id, userId);
        indexSafely(updated);
        return toResponse(updated);
    }

    public List<KnowledgeEntryResponse> list(Long userId, KnowledgeType sourceType) {
        return repository.list(userId, sourceType).stream().map(KnowledgeService::toResponse).toList();
    }

    public void delete(Long userId, Long id) {
        if (repository.delete(id, userId) == 0) {
            throw notFound();
        }
        // 向量失效：MySQL 旧代 SUPERSEDED + pgvector 软删除（fail-soft）
        try {
            hybridRetriever.deindex(id);
        } catch (RuntimeException ex) {
            log.warn("knowledge_deindex_failed id={}", id, ex);
        }
    }

    /** 知识保存/更新后同步向量索引（fail-soft：Embedding/PG 不可用不影响主保存）。 */
    private void indexSafely(KnowledgeEntry entry) {
        try {
            hybridRetriever.indexKnowledge(entry.id(), entry.userId(), null, entry.connectionId(),
                    entry.sourceType(), entry.title(), entry.content());
        } catch (RuntimeException ex) {
            log.warn("knowledge_index_failed id={}", entry.id(), ex);
        }
    }

    /** 服务端鉴权范围内的检索；命中返回 sourceId/version/score/snippet。 */
    public KnowledgeSearchResponse search(Long userId, Long connectionId, String query) {
        return search(userId, connectionId, query, null);
    }

    /**
     * Workspace 感知检索：传入 workspaceId 时，候选 = 本人知识 + 空间内已授权共享知识。
     * 权限在数据库级候选筛选中生效（见 KnowledgeRepository.searchCandidatesInWorkspace），
     * 非成员访问空间抛出 WORKSPACE_NOT_FOUND。
     */
    public KnowledgeSearchResponse search(Long userId, Long connectionId, String query, Long workspaceId) {
        validateScope(userId, connectionId);
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return new KnowledgeSearchResponse(List.of(), RETRIEVAL_MODE);
        }
        List<KnowledgeEntry> candidates;
        if (workspaceId != null) {
            workspaceAccessPolicy.canAccessKnowledgeScope(workspaceId, userId);
            candidates = repository.searchCandidatesInWorkspace(userId, connectionId, workspaceId);
        } else {
            candidates = repository.searchCandidates(userId, connectionId);
        }
        List<KnowledgeSearchResponse.Hit> hits = new ArrayList<>();
        for (KnowledgeEntry entry : candidates) {
            double score = score(entry, normalized);
            if (score > 0) {
                hits.add(new KnowledgeSearchResponse.Hit(
                        entry.id(), entry.sourceType(), entry.title(), entry.version(), score, snippet(entry)));
            }
        }
        hits.sort(Comparator.comparingDouble(KnowledgeSearchResponse.Hit::score).reversed());
        List<KnowledgeSearchResponse.Hit> top = hits.size() > MAX_HITS ? hits.subList(0, MAX_HITS) : hits;
        return new KnowledgeSearchResponse(top, RETRIEVAL_MODE);
    }

    /** 供 Agent 上下文注入：返回裁剪后的知识文本（带来源标识）。 */
    public String knowledgeContext(Long userId, Long connectionId, String question, int maxChars) {
        KnowledgeSearchResponse search = search(userId, connectionId, question);
        StringBuilder builder = new StringBuilder();
        for (KnowledgeSearchResponse.Hit hit : search.items()) {
            String line = "[" + hit.sourceType() + "#" + hit.id() + " v" + hit.version() + "] "
                    + hit.title() + "：" + hit.snippet();
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    public List<KnowledgeSearchResponse.Hit> hits(Long userId, Long connectionId, String question) {
        return search(userId, connectionId, question).items();
    }

    private void validateScope(Long userId, Long connectionId) {
        if (connectionId == null) {
            return;
        }
        if (connectionRepository.findOwned(connectionId, userId).isEmpty()) {
            throw new ApiException(ErrorCode.CONNECTION_NOT_FOUND, HttpStatus.NOT_FOUND, "连接不存在或无权访问");
        }
    }

    private static double score(KnowledgeEntry entry, String normalizedQuery) {
        String title = normalize(entry.title());
        String content = normalize(entry.content());
        if (title.equals(normalizedQuery)) {
            return 1.0;
        }
        if (title.contains(normalizedQuery)) {
            return 0.9;
        }
        double hits = 0;
        for (String token : tokens(normalizedQuery)) {
            if (title.contains(token)) {
                hits += 3;
            } else if (content.contains(token)) {
                hits += 1;
            }
        }
        return Math.min(hits / 5.0, 0.8);
    }

    /** 中文按 2-gram 补充分词，保证「什么是有效订单」能命中「有效订单」。 */
    private static List<String> tokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        for (String word : normalized.split("\\s+")) {
            if (word.length() < 2) {
                continue;
            }
            tokens.add(word);
            if (word.matches("[\\u4e00-\\u9fa5]+")) {
                for (int index = 0; index + 1 < word.length(); index++) {
                    tokens.add(word.substring(index, index + 2));
                }
            }
        }
        return tokens;
    }

    private static String snippet(KnowledgeEntry entry) {
        String content = entry.content().trim();
        return content.length() > SNIPPET_CHARS ? content.substring(0, SNIPPET_CHARS) + "…" : content;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private KnowledgeEntry requireOwned(Long id, Long userId) {
        return repository.findOwned(id, userId).orElseThrow(KnowledgeService::notFound);
    }

    /** 供索引重建等内部用途：校验归属并返回条目。 */
    public KnowledgeEntry requireOwnedEntry(Long id, Long userId) {
        return requireOwned(id, userId);
    }

    private static KnowledgeEntryResponse toResponse(KnowledgeEntry entry) {
        return new KnowledgeEntryResponse(
                entry.id(), entry.sourceType(), entry.title(), entry.content(),
                entry.connectionId(), entry.version(), entry.createdAt(), entry.updatedAt());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.HISTORY_NOT_FOUND, HttpStatus.NOT_FOUND, "知识条目不存在或无权访问");
    }
}
