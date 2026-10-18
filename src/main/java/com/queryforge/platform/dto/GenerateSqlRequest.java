package com.queryforge.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 契约 4.2 的请求体。字段名必须与协同文档完全一致。
 */
public record GenerateSqlRequest(
        @NotBlank(message = "userInput 不能为空")
        @Size(max = 2000, message = "userInput 最长 2000 字符")
        String userInput,

        @Size(max = 4000, message = "schemaContext 最长 4000 字符")
        String schemaContext,

        @Pattern(regexp = "default", message = "strategyName 当前只支持 default")
        String strategyName
) {
}
