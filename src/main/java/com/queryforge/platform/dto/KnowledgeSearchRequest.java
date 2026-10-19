package com.queryforge.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 服务端鉴权范围内的知识检索请求。 */
public record KnowledgeSearchRequest(
        @NotNull(message = "connectionId 不能为空")
        Long connectionId,

        @NotBlank(message = "query 不能为空")
        @Size(max = 500, message = "query 最长 500 字符")
        String query
) {
}
