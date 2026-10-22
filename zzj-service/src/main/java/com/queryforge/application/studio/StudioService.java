package com.queryforge.application.studio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.skill.SkillService;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.application.tool.ToolRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.queryforge.application.agent.AgentConfigContent;
import com.queryforge.domain.studio.AgentDefinition;
import com.queryforge.domain.studio.AgentVersion;
import com.queryforge.infrastructure.persistence.StudioRepository;

/**
 * Agent Studio：草稿 CRUD、发布不可变快照、回滚生成新版本、审计。
 * 配置面与执行面分离：这里只管理配置，实际运行由 RuntimeConfigResolver + AgentRunService 负责。
 */
@Service
public class StudioService {

    private final StudioRepository repository;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final SkillService skillService;

    public StudioService(StudioRepository repository, ObjectMapper objectMapper,
                         ToolRegistry toolRegistry, SkillService skillService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.skillService = skillService;
    }

    // ---- CRUD ----

    public AgentResponse create(Long userId, AgentRequest request) {
        AgentConfigContent content = parseAndValidate(request);
        Long id = repository.insertDefinition(userId, request.name().trim(),
                request.description() == null ? "" : request.description().trim(), writeJson(content));
        repository.audit(userId, "AGENT", id, "CREATE", null, "SUCCESS", null);
        return toResponse(repository.findOwned(id, userId).orElseThrow());
    }

    public AgentResponse update(Long userId, Long id, int expectedRevision, AgentRequest request) {
        requireOwned(id, userId);
        AgentConfigContent content = parseAndValidate(request);
        int updated = repository.updateDraft(id, userId, request.name().trim(),
                request.description() == null ? "" : request.description().trim(),
                writeJson(content), expectedRevision);
        if (updated == 0) {
            throw new ApiException(ErrorCode.AGENT_CONFLICT, HttpStatus.CONFLICT, "Agent 已被他人修改，请刷新后重试");
        }
        repository.audit(userId, "AGENT", id, "UPDATE", null, "SUCCESS", null);
        return toResponse(repository.findOwned(id, userId).orElseThrow());
    }

    public List<AgentResponse> list(Long userId, String status) {
        return repository.listOwned(userId, status).stream().map(this::toResponse).toList();
    }

    public AgentResponse detail(Long userId, Long id) {
        return toResponse(requireOwned(id, userId));
    }

    @Transactional
    public void archive(Long userId, Long id) {
        requireOwned(id, userId);
        if (repository.archive(id, userId) == 0) {
            throw new ApiException(ErrorCode.AGENT_CONFLICT, HttpStatus.CONFLICT, "Agent 已归档");
        }
        repository.audit(userId, "AGENT", id, "ARCHIVE", null, "SUCCESS", null);
    }

    // ---- 发布 / 版本 / 回滚 ----

    @Transactional
    public AgentVersionResponse publish(Long userId, Long id, int expectedRevision, String versionNote) {
        AgentDefinition existing = requireOwned(id, userId);
        AgentConfigContent content = parseContent(existing.draftContent());
        validateTools(content.tools());
        validateSkills(userId, content);
        int versionNo = repository.maxVersionNo(id) + 1;
        String snapshot = writeJson(content);
        String hash = sha256(snapshot);
        // 计算 toolIds JSON 与 skillVersionRefs（发布时固定绑定到已发布 Skill 版本）
        String toolIdsJson = writeJson(content.tools() == null ? List.of() : content.tools());
        Map<Long, Long> skillRefs = resolveSkillRefs(userId, content);
        String skillRefsJson = writeJson(skillRefs);
        Long versionId = repository.insertVersion(id, versionNo, snapshot, skillRefsJson,
                toolIdsJson, content.model(), hash, userId);
        int updated = repository.setPublishedVersion(id, userId, versionId, expectedRevision);
        if (updated == 0) {
            throw new ApiException(ErrorCode.AGENT_CONFLICT, HttpStatus.CONFLICT, "Agent 草稿已被他人修改，请刷新后重试");
        }
        repository.audit(userId, "AGENT", id, "PUBLISH", versionNo, "SUCCESS",
                versionNote == null ? "" : truncate(versionNote, 500));
        return toVersionResponse(repository.findVersion(versionId).orElseThrow());
    }

    /**
     * 回滚：以 sourceVersionId 的内容生成新的已发布快照，更新发布指针，保留旧版本历史。
     * 不回写草稿，旧版本保持只读不变。
     */
    @Transactional
    public AgentVersionResponse rollback(Long userId, Long id, long sourceVersionId, int expectedRevision) {
        AgentDefinition existing = requireOwned(id, userId);
        AgentVersion source = repository.findVersion(sourceVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_VERSION_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "源版本不存在"));
        if (!source.agentId().equals(id)) {
            throw new ApiException(ErrorCode.AGENT_CONFLICT, HttpStatus.BAD_REQUEST, "源版本不属于该 Agent");
        }
        int versionNo = repository.maxVersionNo(id) + 1;
        Long versionId = repository.insertVersion(id, versionNo, source.contentSnapshot(),
                source.skillVersionRefs(), source.toolIds(), source.modelRef(), source.contentHash(), userId);
        int updated = repository.updatePublishedVersion(id, userId, versionId);
        if (updated == 0) {
            throw new ApiException(ErrorCode.AGENT_CONFLICT, HttpStatus.CONFLICT, "回滚失败：Agent 不存在或无权访问");
        }
        repository.audit(userId, "AGENT", id, "ROLLBACK", versionNo, "SUCCESS",
                "from v" + source.versionNo());
        return toVersionResponse(repository.findVersion(versionId).orElseThrow());
    }

    public List<AgentVersionResponse> versions(Long userId, Long id) {
        requireOwned(id, userId);
        return repository.listVersions(id).stream().map(this::toVersionResponse).toList();
    }

    // ---- 解析（供 RuntimeConfigResolver 使用）----

    public AgentConfigContent contentOf(Long userId, Long id) {
        return parseContent(requireOwned(id, userId).draftContent());
    }

    public AgentVersion requireVersionOwned(Long userId, Long versionId) {
        AgentVersion version = repository.findVersion(versionId)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_VERSION_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Agent 版本不存在"));
        requireOwned(version.agentId(), userId);
        return version;
    }

    // ---- 校验 ----

    private AgentConfigContent parseAndValidate(AgentRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "名称不能为空");
        }
        if (request.name().trim().length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "名称最长 120 字符");
        }
        String systemPrompt = request.systemPrompt() == null ? "" : request.systemPrompt();
        if (systemPrompt.length() > 4000) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "系统提示词最长 4000 字符");
        }
        AgentConfigContent content = new AgentConfigContent(
                request.name(), request.description(), systemPrompt, request.model(),
                request.tools(), request.rag(), request.memory(), request.skillIds()).normalized();
        validateTools(content.tools());
        return content;
    }

    private void validateTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        List<String> available = toolRegistry.catalog().stream().map(ToolRegistry.ToolDescriptor::name).toList();
        for (String tool : tools) {
            if (!available.contains(tool)) {
                throw new ApiException(ErrorCode.TOOL_NOT_FOUND, HttpStatus.BAD_REQUEST,
                        "工具不存在或不可用：" + tool);
            }
        }
    }

    private void validateSkills(Long userId, AgentConfigContent content) {
        Map<Long, Long> refs = resolveSkillRefs(userId, content);
        for (Map.Entry<Long, Long> entry : refs.entrySet()) {
            skillService.requirePublishedSkillVersion(userId, entry.getKey(), entry.getValue());
        }
    }

    /** 从 content.skillIds 解析每个 Skill 的当前已发布版本，返回 skillId -> skillVersionId。 */
    private Map<Long, Long> resolveSkillRefs(Long userId, AgentConfigContent content) {
        Map<Long, Long> refs = new LinkedHashMap<>();
        if (content.skillIds() == null) {
            return refs;
        }
        for (Long skillId : content.skillIds()) {
            SkillService.SkillResponse skill = skillService.detail(userId, skillId);
            if (skill.publishedVersionId() == null) {
                throw new ApiException(ErrorCode.SKILL_NOT_PUBLISHED, HttpStatus.BAD_REQUEST,
                        "绑定的 Skill 尚未发布：" + skill.name());
            }
            refs.put(skillId, skill.publishedVersionId());
        }
        return refs;
    }

    private AgentConfigContent parseContent(String json) {
        try {
            return objectMapper.readValue(json, AgentConfigContent.class).normalized();
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Agent 内容解析失败");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "序列化失败");
        }
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

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private AgentDefinition requireOwned(Long id, Long userId) {
        return repository.findOwned(id, userId).orElseThrow(() -> new ApiException(
                ErrorCode.AGENT_NOT_FOUND, HttpStatus.NOT_FOUND, "Agent 不存在或无权访问"));
    }

    private AgentResponse toResponse(AgentDefinition definition) {
        AgentConfigContent content = parseContent(definition.draftContent());
        return new AgentResponse(definition.id(), definition.name(), definition.description(),
                definition.status(), definition.draftRevision(), content, definition.publishedVersionId(),
                definition.createdAt().toString(), definition.updatedAt().toString());
    }

    private AgentVersionResponse toVersionResponse(AgentVersion version) {
        AgentConfigContent content = parseContent(version.contentSnapshot());
        List<String> tools = parseStringList(version.toolIds());
        return new AgentVersionResponse(version.id(), version.agentId(), version.versionNo(), content,
                tools, version.modelRef(), version.contentHash(), version.publishedAt().toString());
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return list == null ? List.of() : list;
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    // ---- 类型 ----

    public record AgentRequest(
            String name, String description, String systemPrompt, String model,
            List<String> tools, AgentConfigContent.RagConfig rag, AgentConfigContent.MemoryConfig memory,
            List<Long> skillIds
    ) {
    }

    public record AgentResponse(
            Long id, String name, String description, String status, int draftRevision,
            AgentConfigContent content, Long publishedVersionId, String createdAt, String updatedAt
    ) {
    }

    public record AgentVersionResponse(
            Long id, Long agentId, int versionNo, AgentConfigContent content,
            List<String> toolIds, String modelRef, String contentHash, String publishedAt
    ) {
    }
}
