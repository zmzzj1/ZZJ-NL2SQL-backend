package com.queryforge.domain.skill;

import java.util.List;

/**
 * Skill 内容（草稿与发布快照共享的 JSON 结构）。
 * triggerScenes：触发场景关键词，用于运行时按任务相关性选择 Skill。
 * guidanceTemplate：指引模板，仅允许固定白名单变量（见 SkillTemplateEngine）。
 * parameterSchema：输入参数 JSON Schema（仅声明，不执行）。
 */
public record SkillContent(
        String triggerScenes,
        String guidanceTemplate,
        String parameterSchema,
        List<String> tags
) {

    public SkillContent normalized() {
        return new SkillContent(
                triggerScenes == null ? "" : triggerScenes.trim(),
                guidanceTemplate == null ? "" : guidanceTemplate,
                parameterSchema == null ? "" : parameterSchema.trim(),
                tags == null ? List.of() : tags
        );
    }
}
