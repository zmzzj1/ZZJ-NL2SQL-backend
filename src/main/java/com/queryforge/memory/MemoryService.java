package com.queryforge.memory;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 长期 Memory：类型化、有来源、可去重、有权限的跨会话记录。
 *
 * 安全语义（对应 P8 指南 6.1）：
 *  - 个人 Memory 默认不共享；工作空间 Memory 按成员权限与共享策略访问。
 *  - 自动提取有限额 + 去重；不自动存储密码/密钥/敏感字段。
 *  - 注入只读相关记录，限制条数与 Token，不把不可信记忆当高优先级命令。
 *  - 删除/停用对后续 Run 立即生效。
 */
@Service
public class MemoryService {

    private static final int MAX_HITS = 5;
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_CONTENT_CHARS = 4000;

    private final MemoryRepository repository;
    private final WorkspaceService workspaceService;

    public MemoryService(MemoryRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
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
            repository.update(existingId, userId, title.trim(), content.trim(),
                    confidence == null ? Confidence.UNVERIFIED.name() : confidence.name());
            return repository.findOwned(existingId, userId).orElseThrow();
        }

        Long id = repository.insert(userId, workspaceId, type.name(), scope.name(), title.trim(), content.trim(),
                sourceRef, confidence == null ? Confidence.UNVERIFIED.name() : confidence.name(), expiresAt);
        repository.insertFingerprint(userId, workspaceId, type.name(), scope.name(), fingerprint, id);
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
        return repository.findOwned(id, userId).orElseThrow();
    }

    @Transactional
    public void disable(Long userId, Long id) {
        requireOwned(id, userId);
        repository.disable(id, userId);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        requireOwned(id, userId);
        repository.delete(id, userId);
    }

    // ---- 检索（供 Agent 上下文注入）----

    /**
     * 检索当前上下文内的相关记忆。
     * scope=PERSONAL 时只检索本人个人记忆；scope=WORKSPACE 时检索空间共享记忆（需成员资格）。
     */
    public List<LongTermMemory> search(Long userId, Long workspaceId, Scope scope, String query) {
        if (scope == Scope.WORKSPACE) {
            workspaceService.requireActiveMember(workspaceId, userId);
        }
        String normalized = normalize(query);
        List<LongTermMemory> candidates = repository.searchCandidates(userId, workspaceId, scope.name());
        List<LongTermMemory> scored = new ArrayList<>();
        for (LongTermMemory memory : candidates) {
            double score = score(memory, normalized);
            if (score > 0) {
                scored.add(memory);
            }
        }
        scored.sort(Comparator.comparingDouble((LongTermMemory m) -> score(m, normalized)).reversed());
        return scored.size() > MAX_HITS ? scored.subList(0, MAX_HITS) : scored;
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
