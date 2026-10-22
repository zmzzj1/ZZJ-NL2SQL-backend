package com.queryforge.domain.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义（来自 tools/list 返回的工具 schema）。
 */
public record McpTool(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
}
