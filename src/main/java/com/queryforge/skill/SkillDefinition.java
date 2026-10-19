package com.queryforge.skill;

import java.time.LocalDateTime;

/** Skill 定义（草稿 + 发布指针）。 */
public record SkillDefinition(
        Long id,
        Long userId,
        String name,
        String slug,
        String description,
        String status,
        int draftRevision,
        String draftContent,
        Long publishedVersionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
