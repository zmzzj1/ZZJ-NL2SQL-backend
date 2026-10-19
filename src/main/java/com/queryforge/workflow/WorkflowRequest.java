package com.queryforge.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建/更新工作流请求。definition 为 DAG JSON，服务端校验后落库。
 */
public record WorkflowRequest(
        @NotBlank String name,
        String description,
        @NotNull @Valid WorkflowGraph definition
) {
}
