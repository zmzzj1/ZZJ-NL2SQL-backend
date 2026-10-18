package com.queryforge.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 契约 4.3 的请求体，只包含 SQL 文本；不接受连接串、账号或密码。
 */
public record ExecuteSqlRequest(
        @NotBlank(message = "sql 不能为空")
        @Size(max = 5000, message = "sql 最长 5000 字符")
        String sql
) {
}
