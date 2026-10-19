package com.queryforge.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.studio.StudioRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill Registry：CRUD、不可变版本发布、停用，以及运行时按需选择已发布 Skill。
 * Skill 是低信任的领域任务指引，不扩权、不执行脚本，仅作为提示词模板注入。
 */
@Service
public class SkillService {

    private final SkillRepository repository;
    private final StudioRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final SkillTemplateEngine templateEngine;

    public SkillService(SkillRepository repository, StudioRepository auditRepository,
                        ObjectMapper objectMapper, SkillTemplateEngine templateEngine) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.templateEngine = templateEngine;
    }

    // ---- CRUD ----

    public SkillResponse create(Long userId, SkillRequest request) {
        SkillContent content = parseAndValidate(request);
        String slug = normalizeSlug(request.slug());
        Long id = repository.insertDefinition(userId, request.name().trim(), slug,
                request.description() == null ? "" : request.description().trim(), writeJson(content));
        auditRepository.audit(userId, "SKILL", id, "CREATE", null, "SUCCESS", null);
        return toResponse(repository.findOwned(id, userId).orElseThrow());
    }

    public SkillResponse update(Long userId, Long id, int expectedRevision, SkillRequest request) {
        SkillDefinition existing = requireOwned(id, userId);
        SkillContent content = parseAndValidate(request);
        String slug = normalizeSlug(request.slug());
        int updated = repository.updateDraft(id, userId, request.name().trim(), slug,
                request.description() == null ? "" : request.description().trim(), writeJson(content), expectedRevision);
        if (updated == 0) {
            throw new ApiException(ErrorCode.AGENT_CONFLICT, HttpStatus.CONFLICT, "Skill 已被他人修改，请刷新后重试");
        }
        auditRepository.audit(userId, "SKILL", id, "UPDATE", null, "SUCCESS", null);
        return toResponse(repository.findOwned(id, userId).orElseThrow());
    }

    public List<SkillResponse> list(Long userId, String status) {
        return repository.listOwned(userId, status).stream().map(this::toResponse).toList();
    }

    public SkillResponse detail(Long userId, Long id) {
        return toResponse(requireOwned(id, userId));
    }

    @Transactional
    public void disable(Long userId, Long id) {
        requireOwned(id, userId);
        if (repository.disable(id, userId) == 0) {
            throw new ApiException(ErrorCode.AGENT_CONFLICT, HttpStatus.CONFLICT, "Skill 已停用");
        }
        auditRepository.audit(userId, "SKILL", id, "ARCHIVE", null, "SUCCESS", null);
    }

    // ---- 发布 / 版本 ----

    @Transactional
    public SkillVersionResponse publish(Long userId, Long id, int expectedRevision) {
        SkillDefinition existing = requireOwned(id, userId);
        SkillContent content = parseContent(existing.draftContent());
        validateTemplateVariables(content);
        int versionNo = repository.maxVersionNo(id) + 1;
        String snapshot = writeJson(content);
        String hash = sha256(snapshot);
        Long versionId = repository.insertVersion(id, versionNo, snapshot, hash, userId);
        int updated = repository.setPublishedVersion(id, userId, versionId, expectedRevision);
        if (updated == 0) {
            throw new ApiException(ErrorCode.AGENT_CONFLICT, HttpStatus.CONFLICT, "Skill 草稿已被他人修改，请刷新后重试");
        }
        auditRepository.audit(userId, "SKILL", id, "PUBLISH", versionNo, "SUCCESS", null);
        SkillVersion version = repository.findVersion(versionId).orElseThrow();
        return new SkillVersionResponse(version.id(), version.skillId(), version.versionNo(),
                version.contentHash(), version.publishedAt().toString());
    }

    public List<SkillVersionResponse> versions(Long userId, Long id) {
        requireOwned(id, userId);
        return repository.listVersions(id).stream()
                .map(v -> new SkillVersionResponse(v.id(), v.skillId(), v.versionNo(),
                        v.contentHash(), v.publishedAt().toString()))
                .toList();
    }

    // ---- 运行时选择 ----

    /**
     * 根据任务问题在给定「已发布 Skill 版本」中选择相关项并渲染指引。
     * 返回有序列表（相关性降序），供 Agent 运行时注入并记录选择证据。
     */
    public List<SelectedSkill> selectAndRender(List<SkillRef> refs, String taskQuestion, String schemaSummary, int maxCount) {
        List<Scored> scored = new ArrayList<>();
        for (SkillRef ref : refs) {
            SkillVersion version = repository.findVersion(ref.skillVersionId()).orElse(null);
            if (version == null) {
                continue; // 引用失效：跳过并记录，不暗中使用
            }
            SkillContent content = parseContent(version.contentSnapshot());
            double score = matchScore(content.triggerScenes(), taskQuestion);
            if (score > 0) {
                String rendered = templateEngine.render(content.guidanceTemplate(),
                        Map.of("taskQuestion", taskQuestion, "schemaSummary", schemaSummary == null ? "" : schemaSummary));
                scored.add(new Scored(ref.skillId(), version.id(), version.versionNo(), content, rendered, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<SelectedSkill> result = new ArrayList<>();
        for (int i = 0; i < Math.min(maxCount, scored.size()); i++) {
            Scored item = scored.get(i);
            result.add(new SelectedSkill(item.skillId(), item.skillVersionId(), item.versionNo(),
                    item.content.triggerScenes(), item.rendered, item.score));
        }
        return result;
    }

    /** 供 Agent 配置绑定校验：确认某个 skillVersionId 属于当前用户且已发布。 */
    public void requirePublishedSkillVersion(Long userId, Long skillId, Long skillVersionId) {
        SkillDefinition definition = repository.findOwned(skillId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.SKILL_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Skill 不存在或无权访问"));
        SkillVersion version = repository.findVersion(skillVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SKILL_VERSION_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Skill 版本不存在"));
        if (!version.skillId().equals(skillId)) {
            throw new ApiException(ErrorCode.SKILL_INVALID_REFERENCE, HttpStatus.BAD_REQUEST, "Skill 版本与 Skill 不匹配");
        }
        if (definition.publishedVersionId() == null) {
            throw new ApiException(ErrorCode.SKILL_NOT_PUBLISHED, HttpStatus.BAD_REQUEST, "Skill 尚未发布");
        }
    }

    // ---- 校验 ----

    private SkillContent parseAndValidate(SkillRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "名称不能为空");
        }
        if (request.name().trim().length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "名称最长 120 字符");
        }
        String slug = normalizeSlug(request.slug());
        if (slug.isEmpty() || slug.length() > 160) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "slug 非法或超长");
        }
        if (request.triggerScenes() == null || request.triggerScenes().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "触发场景不能为空");
        }
        SkillContent content = new SkillContent(
                request.triggerScenes(), request.guidanceTemplate(), request.parameterSchema(), request.tags())
                .normalized();
        validateTemplateVariables(content);
        return content;
    }

    private void validateTemplateVariables(SkillContent content) {
        List<String> unknown = templateEngine.validateVariables(content.guidanceTemplate());
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.SKILL_TEMPLATE_INVALID, HttpStatus.BAD_REQUEST,
                    "指引模板包含未允许的变量：" + String.join(", ", unknown));
        }
    }

    private SkillContent parseContent(String json) {
        try {
            return objectMapper.readValue(json, SkillContent.class).normalized();
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Skill 内容解析失败");
        }
    }

    /** 关键词相关性：中文 2-gram 补充分词，与 KnowledgeService 保持一致的可解释评分。 */
    private static double matchScore(String triggerScenes, String question) {
        String scenes = normalize(triggerScenes);
        String q = normalize(question);
        if (scenes.isBlank() || q.isBlank()) {
            return 0;
        }
        if (q.contains(scenes)) {
            return 1.0;
        }
        double hits = 0;
        for (String token : tokens(scenes)) {
            if (q.contains(token)) {
                hits += 3;
            }
        }
        for (String token : tokens(q)) {
            if (scenes.contains(token)) {
                hits += 1;
            }
        }
        return Math.min(hits / 6.0, 0.8);
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

    private static String normalizeSlug(String slug) {
        if (slug == null) {
            return "";
        }
        return slug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\-_]+", "-")
                .replaceAll("-{2,}", "-").replaceAll("(^-|-$)", "");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "序列化失败");
        }
    }

    private SkillDefinition requireOwned(Long id, Long userId) {
        return repository.findOwned(id, userId).orElseThrow(() -> new ApiException(
                ErrorCode.SKILL_NOT_FOUND, HttpStatus.NOT_FOUND, "Skill 不存在或无权访问"));
    }

    private SkillResponse toResponse(SkillDefinition definition) {
        SkillContent content = parseContent(definition.draftContent());
        return new SkillResponse(definition.id(), definition.name(), definition.slug(),
                definition.description(), definition.status(), definition.draftRevision(),
                content, definition.publishedVersionId(), definition.createdAt().toString(),
                definition.updatedAt().toString());
    }

    // ---- 类型 ----

    public record SkillRequest(
            String name, String slug, String description,
            String triggerScenes, String guidanceTemplate, String parameterSchema, List<String> tags
    ) {
    }

    public record SkillResponse(
            Long id, String name, String slug, String description, String status,
            int draftRevision, SkillContent content, Long publishedVersionId,
            String createdAt, String updatedAt
    ) {
    }

    public record SkillVersionResponse(Long id, Long skillId, int versionNo, String contentHash, String publishedAt) {
    }

    public record SkillRef(Long skillId, Long skillVersionId) {
    }

    public record SelectedSkill(
            Long skillId, Long skillVersionId, int versionNo, String triggerScenes, String renderedGuidance, double score
    ) {
    }

    private record Scored(Long skillId, Long skillVersionId, int versionNo, SkillContent content,
                          String rendered, double score) {
    }
}
