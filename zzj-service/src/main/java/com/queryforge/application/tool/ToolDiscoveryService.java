package com.queryforge.application.tool;

import com.queryforge.application.mcp.McpServerManager;
import com.queryforge.application.skill.SkillService;
import com.queryforge.application.skill.SkillService.SkillResponse;
import com.queryforge.domain.mcp.McpRegisteredTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * P14-E：统一工具发现。组合三类来源，供目录与显式工具选择使用：
 *
 * <ul>
 *   <li>builtin：仅来自 {@link ToolRegistry} 可用只读白名单（call_tool）。</li>
 *   <li>mcp：仅来自当前用户已连接且归属本人的动态注册（call_tool）。</li>
 *   <li>skill：仅来自当前用户真正可访问且已发布的 Skill（inject_prompt，非工具执行）。</li>
 * </ul>
 *
 * 工具描述与输入 schema 属低信任数据，仅作为展示/表单填充，不得被当作高优先级指令。
 * 目录变动体现在下一次获取：断连后旧的 MCP 工具不再返回。
 */
@Service
public class ToolDiscoveryService {

    private final ToolRegistry toolRegistry;
    private final McpServerManager mcpServerManager;
    private final SkillService skillService;

    public ToolDiscoveryService(ToolRegistry toolRegistry, McpServerManager mcpServerManager,
                               SkillService skillService) {
        this.toolRegistry = toolRegistry;
        this.mcpServerManager = mcpServerManager;
        this.skillService = skillService;
    }

    public List<DiscoveredTool> discover(Long userId) {
        List<DiscoveredTool> result = new ArrayList<>();
        // 1) 内置工具
        for (ToolRegistry.ToolDescriptor descriptor : toolRegistry.catalog()) {
            result.add(new DiscoveredTool(
                    "builtin", descriptor.name(), descriptor.name(), descriptor.description(),
                    descriptor.inputSchema(), descriptor.readOnly(), "call_tool", "BUILTIN", null, null));
        }
        // 2) 当前用户已连接的 MCP 工具
        for (McpRegisteredTool tool : mcpServerManager.listConnectedTools(userId)) {
            result.add(new DiscoveredTool(
                    "mcp", tool.toolName(), tool.toolName(), tool.description(), tool.inputSchema(),
                    false, "call_tool", "MCP", tool.serverId(), null));
        }
        // 3) 当前用户已发布的 Skill（仅提示注入，非工具执行）
        for (SkillResponse skill : skillService.list(userId, "PUBLISHED")) {
            result.add(new DiscoveredTool(
                    "skill", skill.slug() == null ? String.valueOf(skill.id()) : skill.slug(),
                    skill.name(), skill.description(), null, false, "inject_prompt", "SKILL",
                    null, skill.id()));
        }
        return result;
    }

    /** 统一目录条目。旧字段（name/description/inputSchema/readOnly）对内置工具继续有效。 */
    public record DiscoveredTool(
            String kind,
            String name,
            String displayName,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            String invocationMode,
            String source,
            Long serverId,
            Long skillId
    ) {
    }
}
