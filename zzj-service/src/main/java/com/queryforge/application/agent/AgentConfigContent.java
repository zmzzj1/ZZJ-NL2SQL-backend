package com.queryforge.application.agent;

import java.util.List;
import java.util.Map;

/**
 * Agent 配置内容（草稿与发布快照共享的 JSON 结构）。
 * 使用 record 表示，由 ObjectMapper 序列化/反序列化。
 * 安全边界：systemPrompt 是低信任文本，注入前由 ResolvedAgentConfig 限制长度并追加在系统提示词之后，
 * 不覆盖平台硬性安全指令（只读 SQL、人工审批、工具权限）。
 */
public record AgentConfigContent(
        String name,
        String description,
        String systemPrompt,
        String model,
        List<String> tools,
        RagConfig rag,
        MemoryConfig memory,
        List<Long> skillIds
) {

    public record RagConfig(boolean enabled, int topK) {
    }

    public record MemoryConfig(boolean enabled, int contextBudget) {
    }

    /** 归一化默认值，避免 null 与越界。 */
    public AgentConfigContent normalized() {
        return new AgentConfigContent(
                name == null ? "" : name.trim(),
                description == null ? "" : description.trim(),
                systemPrompt == null ? "" : systemPrompt,
                model == null || model.isBlank() ? null : model.trim(),
                tools == null ? List.of() : tools,
                rag == null ? new RagConfig(false, 5) : new RagConfig(rag.enabled(), clampTopK(rag.topK())),
                memory == null ? new MemoryConfig(false, 2000) :
                        new MemoryConfig(memory.enabled(), clampBudget(memory.contextBudget())),
                skillIds == null ? List.of() : skillIds
        );
    }

    private static int clampTopK(int topK) {
        return Math.max(1, Math.min(topK, 10));
    }

    private static int clampBudget(int budget) {
        return Math.max(500, Math.min(budget, 8000));
    }
}
