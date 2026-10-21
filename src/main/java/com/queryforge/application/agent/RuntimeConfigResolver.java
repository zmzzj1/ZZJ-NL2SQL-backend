package com.queryforge.application.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.infrastructure.persistence.SkillRepository;
import com.queryforge.domain.studio.AgentVersion;
import com.queryforge.infrastructure.persistence.StudioRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.ResolvedAgentConfig;

/**
 * 统一运行配置解析器：Studio 预览（草稿）、Dashboard、Workflow 均调用同一入口。
 * 解析结果按 Run 固定；一旦创建 Run 保存 agentId/agentVersionId，运行中不再重解析。
 */
@Service
public class RuntimeConfigResolver {

    private final StudioRepository studioRepository;
    private final SkillRepository skillRepository;
    private final ObjectMapper objectMapper;

    public RuntimeConfigResolver(StudioRepository studioRepository, SkillRepository skillRepository,
                                 ObjectMapper objectMapper) {
        this.studioRepository = studioRepository;
        this.skillRepository = skillRepository;
        this.objectMapper = objectMapper;
    }

    /** 解析草稿配置（Studio 测试预览）。skillVersionRefs 取各 Skill 当前已发布版本。 */
    public ResolvedAgentConfig resolveDraft(Long userId, Long agentId) {
        var definition = studioRepository.findOwned(agentId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Agent 不存在或无权访问"));
        AgentConfigContent content = parseContent(definition.draftContent());
        Map<Long, Long> skillRefs = resolveSkillRefs(userId, content.skillIds());
        return new ResolvedAgentConfig(agentId, null, content.name(), content.systemPrompt(), content.model(),
                content.tools(), content.rag().enabled(), content.rag().topK(),
                content.memory().enabled(), content.memory().contextBudget(), skillRefs);
    }

    /** 解析 Agent 的当前已发布版本。 */
    public ResolvedAgentConfig resolvePublished(Long userId, Long agentId) {
        var definition = studioRepository.findOwned(agentId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Agent 不存在或无权访问"));
        if (definition.publishedVersionId() == null) {
            throw new ApiException(ErrorCode.AGENT_NOT_PUBLISHED, HttpStatus.BAD_REQUEST, "Agent 尚未发布");
        }
        return resolvePublishedByVersion(userId, definition.publishedVersionId());
    }

    /**
     * 从 Run 固定的 agentId/agentVersionId 重新解析配置（版本不可变，结果稳定）。
     * 供 Agent Run 与 Workflow 节点执行时统一复用，消除两处重复的解析分支。
     */
    public ResolvedAgentConfig resolveForRun(Long userId, Long agentId, Long agentVersionId) {
        if (agentVersionId != null) {
            return resolvePublishedByVersion(userId, agentVersionId);
        }
        if (agentId != null) {
            return resolvePublished(userId, agentId);
        }
        return ResolvedAgentConfig.defaults();
    }

    /** 解析指定发布版本（固定版本，供 Run 追溯与回滚后引用）。 */
    public ResolvedAgentConfig resolvePublishedByVersion(Long userId, Long versionId) {
        AgentVersion version = studioRepository.findVersion(versionId)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_VERSION_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Agent 版本不存在"));
        studioRepository.findOwned(version.agentId(), userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "Agent 不存在或无权访问"));
        AgentConfigContent content = parseContent(version.contentSnapshot());
        Map<Long, Long> skillRefs = parseSkillRefs(version.skillVersionRefs());
        return new ResolvedAgentConfig(version.agentId(), version.id(), content.name(), content.systemPrompt(),
                content.model(), content.tools(), content.rag().enabled(), content.rag().topK(),
                content.memory().enabled(), content.memory().contextBudget(), skillRefs);
    }

    private Map<Long, Long> resolveSkillRefs(Long userId, java.util.List<Long> skillIds) {
        Map<Long, Long> refs = new LinkedHashMap<>();
        if (skillIds == null) {
            return refs;
        }
        for (Long skillId : skillIds) {
            var definition = skillRepository.findOwned(skillId, userId)
                    .orElseThrow(() -> new ApiException(ErrorCode.SKILL_NOT_FOUND, HttpStatus.NOT_FOUND,
                            "Skill 不存在或无权访问"));
            if (definition.publishedVersionId() == null) {
                throw new ApiException(ErrorCode.SKILL_NOT_PUBLISHED, HttpStatus.BAD_REQUEST,
                        "Skill 尚未发布：" + definition.name());
            }
            refs.put(skillId, definition.publishedVersionId());
        }
        return refs;
    }

    private Map<Long, Long> parseSkillRefs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Long> raw = objectMapper.readValue(json, new TypeReference<Map<String, Long>>() {
            });
            Map<Long, Long> refs = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : raw.entrySet()) {
                refs.put(Long.valueOf(entry.getKey()), entry.getValue());
            }
            return refs;
        } catch (JsonProcessingException | NumberFormatException ex) {
            return Map.of();
        }
    }

    private AgentConfigContent parseContent(String json) {
        try {
            return objectMapper.readValue(json, AgentConfigContent.class).normalized();
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "配置解析失败");
        }
    }
}
