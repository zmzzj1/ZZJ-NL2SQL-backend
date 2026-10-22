package com.queryforge.application.tool;

import com.queryforge.domain.agent.AgentRun;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Agent Run 的工具白名单读取能力：ToolRegistry 对 Agent 域的唯一依赖点。
 *
 * <p>运行未关联 Agent、运行不存在或白名单为空时返回空列表（表示放行，保持默认行为）；
 * 白名单 JSON 解析失败同样按放行处理，与既有语义一致。
 */
@Service
public class AgentRunToolPolicy {

    private final AgentRunRepository agentRunRepository;
    private final ObjectMapper objectMapper;

    public AgentRunToolPolicy(AgentRunRepository agentRunRepository, ObjectMapper objectMapper) {
        this.agentRunRepository = agentRunRepository;
        this.objectMapper = objectMapper;
    }

    /** 返回运行允许执行的工具名；空列表表示不限制（放行）。 */
    public List<String> allowedToolNames(Long agentRunId) {
        AgentRun run = agentRunRepository.findRun(agentRunId).orElse(null);
        if (run == null || run.agentId() == null) {
            return List.of();
        }
        return parseAllowedTools(run.toolPolicyHash());
    }

    private List<String> parseAllowedTools(String json) {
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
}
