package com.queryforge.knowledge;

import com.queryforge.knowledge.KnowledgeType;
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
