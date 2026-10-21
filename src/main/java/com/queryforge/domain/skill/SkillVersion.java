package com.queryforge.domain.skill;

import java.time.LocalDateTime;

/** Skill 发布版本（不可变快照）。 */
public record SkillVersion(
        Long id,
        Long skillId,
        int versionNo,
        String contentSnapshot,
        String contentHash,
        Long publishedBy,
        LocalDateTime publishedAt
) {
}
