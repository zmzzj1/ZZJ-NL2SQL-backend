package com.queryforge.platform.entity;

import java.time.LocalDateTime;

/**
 * 用户维护的业务术语或经过审核的 SQL 示例。
 * connectionId 为空表示用户级共享，否则只在指定数据源作用域内检索。
 */
public record KnowledgeEntry(
        Long id,
        Long userId,
        Long connectionId,
        KnowledgeType sourceType,
        String title,
        String content,
        String status,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
