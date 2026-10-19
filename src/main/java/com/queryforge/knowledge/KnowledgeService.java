package com.queryforge.knowledge;

import com.queryforge.knowledge.KnowledgeEntryRequest;
import com.queryforge.knowledge.KnowledgeEntryResponse;
import com.queryforge.knowledge.KnowledgeSearchResponse;
import com.queryforge.knowledge.KnowledgeEntry;
import com.queryforge.knowledge.KnowledgeType;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.datasource.repository.ConnectionRepository;
import com.queryforge.knowledge.KnowledgeRepository;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 业务术语与审核 SQL 示例的维护与检索。
 * 当前使用明确标注的关键词相关性检索（向量语义检索需要 Embedding 环境，另行评估）。
 */
@Service
public class KnowledgeService {

    private static final int MAX_HITS = 5;
    private static final int SNIPPET_CHARS = 200;
    private static final String RETRIEVAL_MODE = "KEYWORD_DEGRADED";

    private final KnowledgeRepository repository;
    private final ConnectionRepository connectionRepository;

    public KnowledgeService(KnowledgeRepository repository, ConnectionRepository connectionRepository) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
    }

    public KnowledgeEntryResponse create(Long userId, KnowledgeEntryRequest request) {
        validateScope(userId, request.connectionId());
        Long id = repository.insert(
                userId, request.connectionId(), request.sourceType(), request.title().trim(), request.content().trim());
        return toResponse(repository.findOwned(id, userId).orElseThrow(KnowledgeService::notFound));
    }

    public KnowledgeEntryResponse update(Long userId, Long id, KnowledgeEntryRequest request) {
        KnowledgeEntry existing = requireOwned(id, userId);
        validateScope(userId, request.connectionId());
        int newVersion = existing.version() + 1;
        if (repository.update(id, userId, request.connectionId(), request.title().trim(),
                request.content().trim(), newVersion) == 0) {
            throw notFound();
        }
        return toResponse(requireOwned(id, userId));
    }

    public List<KnowledgeEntryResponse> list(Long userId, KnowledgeType sourceType) {
        return repository.list(userId, sourceType).stream().map(KnowledgeService::toResponse).toList();
    }

    public void delete(Long userId, Long id) {
        if (repository.delete(id, userId) == 0) {
            throw notFound();
        }
    }

    /** 服务端鉴权范围内的检索；命中返回 sourceId/version/score/snippet。 */
    public KnowledgeSearchResponse search(Long userId, Long connectionId, String query) {
        validateScope(userId, connectionId);
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return new KnowledgeSearchResponse(List.of(), RETRIEVAL_MODE);
        }
        List<KnowledgeEntry> candidates = repository.searchCandidates(userId, connectionId);
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

    private static KnowledgeEntryResponse toResponse(KnowledgeEntry entry) {
        return new KnowledgeEntryResponse(
                entry.id(), entry.sourceType(), entry.title(), entry.content(),
                entry.connectionId(), entry.version(), entry.createdAt(), entry.updatedAt());
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.HISTORY_NOT_FOUND, HttpStatus.NOT_FOUND, "知识条目不存在或无权访问");
    }
}
