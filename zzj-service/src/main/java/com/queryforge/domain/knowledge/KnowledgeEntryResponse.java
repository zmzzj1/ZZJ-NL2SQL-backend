package com.queryforge.domain.knowledge;

import com.queryforge.domain.knowledge.KnowledgeType;
import java.time.LocalDateTime;

public record KnowledgeEntryResponse(
        Long id,
        KnowledgeType sourceType,
        String title,
        String content,
        Long connectionId,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
