package com.queryforge.domain.mcp;

import java.time.LocalDateTime;

/**
 * 用户级 MCP 服务配置的持久化实体。
 *
 * <p>{@code status} 是配置是否启用的<b>持久意图</b>；连接是否真实在线是<b>运行时事实</b>，
 * 两者由 {@code McpServerManager} 分别维护，不得混用。环境元数据只保存非敏感信息，
 * 任何 secret/token 等凭据一律不得入库。</p>
 */
public record McpServerEntity(
        Long id,
        Long userId,
        String name,
        String transport,
        String endpoint,
        String status,
        String argsJson,
        String envMetaJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
