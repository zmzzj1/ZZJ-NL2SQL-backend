package com.queryforge.mcp;

import com.queryforge.identity.AppUserPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * X4 MCP 端点：预配置服务列表、健康/工具发现、只读工具调用。
 * 不含访问凭据，不提供任意 URL/命令注册。
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

    private final McpService service;

    public McpController(McpService service) {
        this.service = service;
    }

    @GetMapping("/servers")
    public List<McpService.McpServerView> servers(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.servers();
    }

    @PostMapping("/servers/{name}/test")
    public List<McpTool> test(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable String name) {
        return service.testServer(name);
    }

    @PostMapping("/servers/{name}/tools/{toolName}/call")
    public String call(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable String name,
            @PathVariable String toolName,
            @RequestBody Map<String, Object> arguments) {
        return service.callTool(name, toolName, arguments);
    }
}
