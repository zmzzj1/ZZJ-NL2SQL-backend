package com.queryforge.query.execution;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * P3 契约：执行必须携带与生成相同的 connectionId；
 * conversationId 可选，用于把本次执行关联到会话。
 */
public record ExecuteSqlRequest(
        @NotNull(message = "connectionId 不能为空")
        Long connectionId,

        Long conversationId,

        @NotBlank(message = "sql 不能为空")
        @Size(max = 5000, message = "sql 最长 5000 字符")
        String sql
) {
}
