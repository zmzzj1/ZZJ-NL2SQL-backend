package com.queryforge.trigger.tool;

import com.queryforge.domain.identity.AppUserPrincipal;
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
import com.queryforge.application.tool.ToolDiscoveryService;
import com.queryforge.application.tool.ToolRegistry;
import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.domain.tool.ToolInvocationResult;

/**
 * X1 工具端点：目录（GET）与只读工具调用（POST）。工具名白名单 + 服务端参数校验 + 审计。
 * P14-E 新增 {@code /discovery} 提供 builtin/mcp/skill 统一目录，旧 {@code /} 保持稳定以兼容 ToolPicker。
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolRegistry registry;
    private final ToolDiscoveryService discoveryService;

    public ToolController(ToolRegistry registry, ToolDiscoveryService discoveryService) {
        this.registry = registry;
        this.discoveryService = discoveryService;
    }

    @GetMapping
    public List<ToolRegistry.ToolDescriptor> catalog() {
        return registry.catalog();
    }

    /** P14-E：用户作用域统一工具目录（内置 / MCP / Skill），含 kind/source/invocationMode/serverId 等规范字段。 */
    @GetMapping("/discovery")
    public List<ToolDiscoveryService.DiscoveredTool> discovery(
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return discoveryService.discover(principal.id());
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
