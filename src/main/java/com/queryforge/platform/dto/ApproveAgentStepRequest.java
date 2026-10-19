package com.queryforge.platform.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 只批准前端刚展示过的 SQL 版本；clientRequestId 仅用于排查重复提交。
 */
public record ApproveAgentStepRequest(
        @NotNull(message = "sqlVersion 不能为空")
        Integer sqlVersion,

        @NotNull(message = "confirm 不能为空")
        Boolean confirm,

        @Size(max = 64, message = "clientRequestId 最长 64 字符")
        String clientRequestId
) {
}
