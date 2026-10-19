package com.queryforge.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务配置（仅预配置受信服务，不含凭据）。transport 当前仅支持 stdio。
 */
public record McpServerConfig(
        String name,
        String transport,
        String command,
        List<String> args
) {
}
