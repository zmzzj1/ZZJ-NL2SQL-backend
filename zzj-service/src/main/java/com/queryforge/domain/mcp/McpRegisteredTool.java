package com.queryforge.domain.mcp;

import java.util.Map;

/**
 * 用户作用域中注册的真实 MCP 工具定义。
 *
 * <p>来自外部 {@code tools/list} 的返回，属于<b>低信任数据</b>：description 与 inputSchema
 * 不得作为高优先级指令或权限来源。执行定位必须能唯一解析
 * {@code (ownerUserId, serverId, toolName)}，禁止跨用户串台。</p>
 */
public record McpRegisteredTool(
        Long serverId,
        Long ownerUserId,
        String toolName,
        String description,
        Map<String, Object> inputSchema,
        boolean readOnly
) {
}
