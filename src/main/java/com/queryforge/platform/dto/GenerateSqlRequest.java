package com.queryforge.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * P2 契约：Schema 由服务端按 connectionId 读取，客户端不再传 schemaContext。
 */
public record GenerateSqlRequest(
        @NotBlank(message = "userInput 不能为空")
        @Size(max = 2000, message = "userInput 最长 2000 字符")
        String userInput,

        @NotNull(message = "connectionId 不能为空")
        Long connectionId,

        @Pattern(regexp = "default", message = "strategyName 当前只支持 default")
        String strategyName
) {
}
