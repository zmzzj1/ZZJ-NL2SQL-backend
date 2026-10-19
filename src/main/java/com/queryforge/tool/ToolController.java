package com.queryforge.tool;

import com.queryforge.identity.AppUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * X1 工具端点：目录（GET）与只读工具调用（POST）。工具名白名单 + 服务端参数校验 + 审计。
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolRegistry registry;

    public ToolController(ToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<ToolRegistry.ToolDescriptor> catalog() {
        return registry.catalog();
    }

    @PostMapping("/invoke")
    public ToolInvocationResult invoke(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody InvokeToolRequest request) {
        ToolInvocation invocation = new ToolInvocation(
                principal.id(), request.toolName(), request.conversationId(), request.agentRunId(),
                request.agentStepId(), request.workflowRunId(), request.workflowNodeId());
        return registry.invoke(invocation, request.arguments());
    }

    public record InvokeToolRequest(
            @NotNull String toolName,
            Long conversationId,
            Long agentRunId,
            Long agentStepId,
            Long workflowRunId,
            Long workflowNodeId,
            Map<String, Object> arguments
    ) {
    }
}
