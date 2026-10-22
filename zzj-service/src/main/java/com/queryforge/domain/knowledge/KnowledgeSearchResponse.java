package com.queryforge.domain.knowledge;

import com.queryforge.domain.knowledge.KnowledgeType;
import java.util.List;

/** 检索结果：带来源标识、版本与评分的摘要，供前端展示与提示词注入。 */
public record KnowledgeSearchResponse(List<Hit> items, String retrievalMode) {

    public record Hit(
            Long id,
            KnowledgeType sourceType,
            String title,
            int version,
            double score,
            String snippet
    ) {
    }
}
