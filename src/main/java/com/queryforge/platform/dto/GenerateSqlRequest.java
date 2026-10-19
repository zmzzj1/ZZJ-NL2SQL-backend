package com.queryforge.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * P3 契约：Schema 由服务端按 connectionId 读取；
 * conversationId 可选，传入时必须属于当前用户且与连接一致。
 */
public record GenerateSqlRequest(
        @NotBlank(message = "userInput 不能为空")
        @Size(max = 2000, message = "userInput 最长 2000 字符")
        String userInput,

        @NotNull(message = "connectionId 不能为空")
        Long connectionId,

        Long conversationId,

        @Pattern(regexp = "default", message = "strategyName 当前只支持 default")
        String strategyName
) {
}
