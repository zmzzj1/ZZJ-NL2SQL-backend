package com.queryforge.application.memory;

import com.queryforge.infrastructure.rag.EmbeddingClient;
import com.queryforge.infrastructure.rag.PgVectorStore;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.application.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.queryforge.domain.memory.LongTermMemory;
import com.queryforge.infrastructure.persistence.MemoryRepository;

/**
 * 长期 Memory：类型化、有来源、可去重、有权限的跨会话记录。
 *
 * 安全语义（对应 P8 指南 6.1）：
 *  - 个人 Memory 默认不共享；工作空间 Memory 按成员权限与共享策略访问。
 *  - 自动提取有限额 + 去重；不自动存储密码/密钥/敏感字段。
 *  - 注入只读相关记录，限制条数与 Token，不把不可信记忆当高优先级命令。
 *  - 删除/停用对后续 Run 立即生效。
 *
 * P12 S3：Memory 向量投影到 PostgreSQL pgvector（{@link PgVectorStore}，vector_type=MEMORY）：
 *  - 保存/更新/去重时写入真实 Embedding；删除/停用时同步软失效，过期内容不再注入。
 *  - 检索在 MySQL 授权候选集之上融合向量相似度信号；PG 命中只作打分信号，
 *    最终是否返回完全由 MySQL 的 owner/scope/status/expires 过滤决定，不跨用户泄露。
 *  - Embedding 或 PG 不可用时 fail-soft 退化为纯关键词检索，不影响 Memory 主链路。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int MAX_HITS = 5;
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_CONTENT_CHARS = 4000;
    private static final int VECTOR_CANDIDATES = 16;

    private final MemoryRepository repository;
    private final WorkspaceService workspaceService;
    private final EmbeddingClient embeddingClient;
    private final PgVectorStore pgVectorStore;

    public MemoryService(MemoryRepository repository, WorkspaceService workspaceService,
                         EmbeddingClient embeddingClient, PgVectorStore pgVectorStore) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.embeddingClient = embeddingClient;
        this.pgVectorStore = pgVectorStore;
    }

    // ---- 类型与常量 ----

    public enum MemoryType {
        PROFILE, TASK, FACT, EPISODIC
    }

    public enum Scope {
        PERSONAL, WORKSPACE
    }

    public enum Confidence {
        UNVERIFIED, VERIFIED, REJECTED
    }

    // ---- CRUD ----

    @Transactional
    public LongTermMemory create(Long userId, Long workspaceId, MemoryType type, Scope scope,
                                 String title, String content, String sourceRef, Confidence confidence,
                                 LocalDateTime expiresAt) {
        validate(type, scope, title, content);
        // 工作空间记忆需成员资格，且必须是 WORKSPACE scope
        if (scope == Scope.WORKSPACE) {
            if (workspaceId == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "工作空间记忆需指定空间");
            }
            workspaceService.requireActiveMember(workspaceId, userId);
        } else {
            // 个人记忆忽略 workspaceId
            workspaceId = null;
        }

        String fingerprint = fingerprint(type, scope, title, content);
        var existing = repository.findFingerprint(userId, workspaceId, type.name(), scope.name(), fingerprint);
        if (existing.isPresent()) {
            // 去重：更新已有记录而非新增
            Long existingId = existing.get();
            String safeTitle = title.trim();
            String safeContent = content.trim();
            repository.update(existingId, userId, safeTitle, safeContent,
                    confidence == null ? Confidence.UNVERIFIED.name() : confidence.name());
            reindexMemory(existingId, userId, workspaceId, safeTitle, safeContent);
            return repository.findOwned(existingId, userId).orElseThrow();
        }

        String safeTitle = title.trim();
        String safeContent = content.trim();
        Long id = repository.insert(userId, workspaceId, type.name(), scope.name(), safeTitle, safeContent,
                sourceRef, confidence == null ? Confidence.UNVERIFIED.name() : confidence.name(), expiresAt);
        repository.insertFingerprint(userId, workspaceId, type.name(), scope.name(), fingerprint, id);
        reindexMemory(id, userId, workspaceId, safeTitle, safeContent);
        return repository.findOwned(id, userId).orElseThrow();
    }

    public List<LongTermMemory> list(Long userId, MemoryType type) {
        return repository.listOwned(userId, type == null ? null : type.name());
    }

    public LongTermMemory detail(Long userId, Long id) {
        return requireOwned(id, userId);
    }

    @Transactional
    public LongTermMemory update(Long userId, Long id, String title, String content, Confidence confidence) {
        requireOwned(id, userId);
        String safeTitle = title == null ? "" : title.trim();
        String safeContent = content == null ? "" : content.trim();
        if (safeTitle.isBlank() || safeContent.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "标题与内容不能为空");
        }
        repository.update(id, userId, safeTitle, safeContent,
                confidence == null ? Confidence.UNVERIFIED.name() : confidence.name());
        LongTermMemory updated = repository.findOwned(id, userId).orElseThrow();
        reindexMemory(id, userId, updated.workspaceId(), safeTitle, safeContent);
        return updated;
    }

    @Transactional
    public void disable(Long userId, Long id) {
        requireOwned(id, userId);
        repository.disable(id, userId);
        deactivateVector(id);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        requireOwned(id, userId);
        repository.delete(id, userId);
        deactivateVector(id);
    }

    // ---- 检索（供 Agent 上下文注入）----

    /**
     * 检索当前上下文内的相关记忆（关键词 + pgvector 语义融合）。
     * scope=PERSONAL 时只检索本人个人记忆；scope=WORKSPACE 时检索空间共享记忆（需成员资格）。
     * 向量命中只对 MySQL 授权候选集内的记录提升得分，跨用户/失效记录不会因 PG 命中进入结果。
     */
    public List<LongTermMemory> search(Long userId, Long workspaceId, Scope scope, String query) {
        if (scope == Scope.WORKSPACE) {
            workspaceService.requireActiveMember(workspaceId, userId);
        }
        String normalized = normalize(query);
        List<LongTermMemory> candidates = repository.searchCandidates(userId, workspaceId, scope.name());
        Map<Long, Double> vectorScores = vectorScores(userId, workspaceId, scope, query);
        List<Scored> scored = new ArrayList<>();
        for (LongTermMemory memory : candidates) {
            double best = Math.max(score(memory, normalized), vectorScores.getOrDefault(memory.id(), 0.0));
            if (best > 0) {
                scored.add(new Scored(memory, best));
            }
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score()).reversed());
        return scored.size() > MAX_HITS
                ? scored.subList(0, MAX_HITS).stream().map(Scored::memory).toList()
                : scored.stream().map(Scored::memory).toList();
    }

    /** 带得分的中间结构（避免排序时重复计算）。 */
    private record Scored(LongTermMemory memory, double score) {
    }

    /**
     * pgvector 语义信号：个人按 owner 过滤；工作空间按 workspace_id 过滤（跨 owner，授权由 MySQL 复核）。
     * Embedding 或 PG 不可用时返回空 Map，检索退化为纯关键词。
     */
    private Map<Long, Double> vectorScores(Long userId, Long workspaceId, Scope scope, String query) {
        try {
            List<Double> queryVector = embeddingClient.embed(query);
            List<PgVectorStore.Hit> hits = scope == Scope.WORKSPACE
                    ? pgVectorStore.similaritySearchInWorkspace(
                            PgVectorStore.TYPE_MEMORY, workspaceId, queryVector, VECTOR_CANDIDATES)
                    : pgVectorStore.similaritySearch(
                            PgVectorStore.TYPE_MEMORY, userId, null, queryVector, VECTOR_CANDIDATES);
            Map<Long, Double> scores = new HashMap<>();
            for (PgVectorStore.Hit hit : hits) {
                double similarity = Math.max(0.0, Math.min(1.0, 1.0 - hit.cosineDistance()));
                scores.merge(hit.sourceId(), similarity, Math::max);
            }
            return scores;
        } catch (RuntimeException ex) {
            log.warn("memory_vector_search_failed scope={} userId={}", scope, userId, ex);
            return Map.of();
        }
    }

    /** 写入/更新向量投影（先软失效旧向量再写新，保证内容变更不残留过期向量）。fail-soft。 */
    private void reindexMemory(Long memoryId, Long userId, Long workspaceId, String title, String content) {
        try {
            List<Double> vector = embeddingClient.embed(title + "：" + content);
            pgVectorStore.deactivate(PgVectorStore.TYPE_MEMORY, memoryId);
            // Memory 不限连接作用域：connectionId 传 null，workspaceId 按作用域投影。
            pgVectorStore.add(PgVectorStore.TYPE_MEMORY, memoryId, userId, null, workspaceId,
                    vector, snippet(title));
        } catch (RuntimeException ex) {
            log.warn("memory_vector_index_failed memoryId={}", memoryId, ex);
        }
    }

    /** 删除/停用时同步失效向量，后续检索不再注入。fail-soft。 */
    private void deactivateVector(Long memoryId) {
        try {
            pgVectorStore.deactivate(PgVectorStore.TYPE_MEMORY, memoryId);
        } catch (RuntimeException ex) {
            log.warn("memory_vector_deindex_failed memoryId={}", memoryId, ex);
        }
    }

    /**
     * 供 Agent 上下文注入：返回裁剪后的记忆文本（带类型与来源标识）。
     */
    public String memoryContext(Long userId, Long workspaceId, Scope scope, String query, int maxChars) {
        List<LongTermMemory> memories = search(userId, workspaceId, scope, query);
        StringBuilder builder = new StringBuilder();
        for (LongTermMemory memory : memories) {
            String line = "[" + memory.memoryType() + "]" + memory.title() + "：" + truncate(memory.content(), 300);
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private void validate(MemoryType type, Scope scope, String title, String content) {
        if (type == null || scope == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "类型与作用域不能为空");
        }
        String safeTitle = title == null ? "" : title.trim();
        String safeContent = content == null ? "" : content.trim();
        if (safeTitle.isBlank() || safeTitle.length() > MAX_TITLE_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "标题为空或超长");
        }
        if (safeContent.isBlank() || safeContent.length() > MAX_CONTENT_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "内容为空或超长");
        }
    }

    private LongTermMemory requireOwned(Long id, Long userId) {
        return repository.findOwned(id, userId).orElseThrow(() -> new ApiException(
                ErrorCode.MEMORY_NOT_FOUND, HttpStatus.NOT_FOUND, "记忆不存在或无权访问"));
    }

    private static double score(LongTermMemory memory, String normalizedQuery) {
        String title = normalize(memory.title());
        String content = normalize(memory.content());
        if (title.contains(normalizedQuery) || content.contains(normalizedQuery)) {
            return 1.0;
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

    private static List<String> tokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        for (String word : normalized.split("\\s+")) {
            if (word.length() < 2) {
                continue;
            }
            tokens.add(word);
            if (word.matches("[\\u4e00-\\u9fa5]+")) {
                for (int i = 0; i + 1 < word.length(); i++) {
                    tokens.add(word.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim();
    }

    private static String truncate(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) + "…" : value;
    }

    /** 向量投影的内容摘要（200 字符，与知识侧一致）。 */
    private static String snippet(String value) {
        String content = value == null ? "" : value.trim();
        return content.length() > 200 ? content.substring(0, 200) + "…" : content;
    }

    private static String fingerprint(MemoryType type, Scope scope, String title, String content) {
        String raw = type.name() + "|" + scope.name() + "|" + normalize(title) + "|" + normalize(content);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
