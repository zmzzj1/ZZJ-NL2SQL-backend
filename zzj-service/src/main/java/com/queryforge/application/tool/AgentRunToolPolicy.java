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
 * <p>P14-A：区分三种状态，避免把“配置解析损坏”误当成“默认不限制”而放行：
 * <ul>
 *   <li>{@link ToolPolicyState#UNRESTRICTED}：运行未关联 Agent 或未发布工具策略 → 真正的默认不限制。</li>
 *   <li>{@link ToolPolicyState#ALLOWED}：已发布策略解析成功 → 只允许列表内工具。</li>
 *   <li>{@link ToolPolicyState#CORRUPT}：已发布策略存在但解析失败 → 显式工具分支拒绝而非降级放行。</li>
 * </ul>
 */
@Service
public class AgentRunToolPolicy {

    private final AgentRunRepository agentRunRepository;
    private final ObjectMapper objectMapper;

    public AgentRunToolPolicy(AgentRunRepository agentRunRepository, ObjectMapper objectMapper) {
        this.agentRunRepository = agentRunRepository;
        this.objectMapper = objectMapper;
    }

    /** 返回运行允许执行的工具名；空列表表示不限制（放行），保持既有语义。 */
    public List<String> allowedToolNames(Long agentRunId) {
        AgentRun run = agentRunRepository.findRun(agentRunId).orElse(null);
        if (run == null || run.agentId() == null) {
            return List.of();
        }
        return parseAllowedTools(run.toolPolicyHash());
    }

    /** P14-A：三态裁决，供显式工具快捷路径区分“默认不限制”与“配置损坏”。 */
    public ToolPolicyDecision decide(Long agentRunId) {
        AgentRun run = agentRunRepository.findRun(agentRunId).orElse(null);
        if (run == null || run.agentId() == null) {
            return ToolPolicyDecision.unrestricted();
        }
        String json = run.toolPolicyHash();
        if (json == null || json.isBlank()) {
            return ToolPolicyDecision.unrestricted();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return new ToolPolicyDecision(
                    ToolPolicyState.ALLOWED, list == null ? List.of() : List.copyOf(list));
        } catch (JsonProcessingException ex) {
            return new ToolPolicyDecision(ToolPolicyState.CORRUPT, List.of());
        }
    }

    /** 显式工具分支的严格判定：配置损坏或不在白名单都拒绝。 */
    public boolean allowsStrictly(Long agentRunId, String toolName) {
        return decide(agentRunId).allows(toolName);
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

    public enum ToolPolicyState {
        UNRESTRICTED,
        ALLOWED,
        CORRUPT
    }

    public record ToolPolicyDecision(ToolPolicyState state, List<String> allowedTools) {

        public static ToolPolicyDecision unrestricted() {
            return new ToolPolicyDecision(ToolPolicyState.UNRESTRICTED, List.of());
        }

        public boolean allows(String toolName) {
            return switch (state) {
                case UNRESTRICTED -> true;
                case ALLOWED -> allowedTools.contains(toolName);
                case CORRUPT -> false;
            };
        }
    }
}
