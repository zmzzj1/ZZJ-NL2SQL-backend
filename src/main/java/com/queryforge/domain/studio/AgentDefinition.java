package com.queryforge.domain.studio;

import java.time.LocalDateTime;

/** Agent 定义（草稿 + 发布指针）。 */
public record AgentDefinition(
        Long id,
        Long userId,
        String name,
        String description,
        String status,
        int draftRevision,
        String draftContent,
        Long publishedVersionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
