package com.queryforge.config;

import java.util.List;
import java.util.Map;

/**
 * 解析后的运行配置：统一供 Studio 预览、Dashboard 与 Workflow 使用。
 * 所有字段不可变，一旦创建 Run 就固定，不随草稿/发布变更而变。
 */
public record ResolvedAgentConfig(
        Long agentId,
        Long agentVersionId,
        String name,
        String systemPrompt,
        String model,
        List<String> allowedToolIds,
        boolean ragEnabled,
        int ragTopK,
        boolean memoryEnabled,
        int memoryContextBudget,
        /** skillId -> skillVersionId 的不可变引用 */
        Map<Long, Long> skillVersionRefs
) {

    /** 无 Agent 配置时的默认行为（兼容旧协议）：全工具、RAG 开启、Memory 开启。 */
    public static ResolvedAgentConfig defaults() {
        return new ResolvedAgentConfig(
                null, null, "", "", null, List.of(), true, 5, true, 2000, Map.of());
    }

    /** 是否允许某个工具（默认配置放行全部，由 ToolRegistry 自身白名单兜底）。 */
    public boolean allowsTool(String toolName) {
        return agentId == null || allowedToolIds.isEmpty() || allowedToolIds.contains(toolName);
    }
}
