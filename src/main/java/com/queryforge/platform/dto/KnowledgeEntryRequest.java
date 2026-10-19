package com.queryforge.platform.dto;

import com.queryforge.platform.entity.KnowledgeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 创建/更新知识条目。 */
public record KnowledgeEntryRequest(
        @NotNull(message = "sourceType 不能为空")
        KnowledgeType sourceType,

        @NotBlank(message = "title 不能为空")
        @Size(max = 200, message = "title 最长 200 字符")
        String title,

        @NotBlank(message = "content 不能为空")
        @Size(max = 5000, message = "content 最长 5000 字符")
        String content,

        Long connectionId
) {
}
