package com.queryforge.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * P2 契约：执行必须携带与生成相同的 connectionId。
 */
public record ExecuteSqlRequest(
        @NotNull(message = "connectionId 不能为空")
        Long connectionId,

        @NotBlank(message = "sql 不能为空")
        @Size(max = 5000, message = "sql 最长 5000 字符")
        String sql
) {
}
