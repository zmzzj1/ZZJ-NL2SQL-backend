package com.queryforge.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 预配置的受信 MCP 服务白名单。只允许服务端预定义的服务，禁止用户任意注册 URL 或命令。
 */
@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(List<McpServerConfig> servers) {

    public List<McpServerConfig> servers() {
        return servers == null ? List.of() : servers;
    }
}
