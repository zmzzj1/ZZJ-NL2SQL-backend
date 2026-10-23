package com.queryforge.application.tool;

import static com.queryforge.application.mcp.McpTestFakes.entity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.application.mcp.McpServerManager;
import com.queryforge.application.mcp.McpTestFakes;
import com.queryforge.application.skill.SkillService;
import com.queryforge.domain.mcp.McpTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 统一工具发现：三类来源正确标注，且 MCP/Skill 严格按当前用户作用域收敛。 */
class ToolDiscoveryServiceTest {

    /** 只覆盖运行时选择所需的 list(userId, status)，避免引入持久化依赖。 */
    private static final class FakeSkillService extends SkillService {
        List<SkillResponse> response = List.of();
        String lastStatus;

        FakeSkillService() {
            super(null, null, new ObjectMapper(), null);
        }

        @Override
        public List<SkillResponse> list(Long userId, String status) {
            lastStatus = status;
            return response;
        }
    }

    private ToolDiscoveryService service(McpServerManager manager, FakeSkillService skills) {
        McpTestFakes.FakeTool builtin = new McpTestFakes.FakeTool("echo");
        ToolRegistry registry = new ToolRegistry(
                List.of(builtin), new McpTestFakes.FakeAudit(), new ObjectMapper(), new McpTestFakes.FakePolicy());
        return new ToolDiscoveryService(registry, manager, skills);
    }

    private static McpServerManager managerWithWeatherFor(Long userId) {
        McpTestFakes.FakeTransport transport = new McpTestFakes.FakeTransport(
                List.of(new McpTool("weather", "查天气", Map.of("type", "object"))), false);
        McpServerManager manager = new McpServerManager(new McpTestFakes.FakeRepo(), new McpTestFakes.FakeFactory(transport));
        manager.connect(userId, entity(1L, userId));
        return manager;
    }

    @Test
    void discoveryMarksAllThreeKindsCorrectly() {
        McpServerManager manager = managerWithWeatherFor(7L);
        FakeSkillService skills = new FakeSkillService();
        skills.response = List.of(new SkillService.SkillResponse(
                5L, "写作助手", "writer", "desc", "PUBLISHED", 1, null, 9L, "", ""));

        List<ToolDiscoveryService.DiscoveredTool> tools = service(manager, skills).discover(7L);

        assertEquals(3, tools.size());
        assertTrue(tools.stream().anyMatch(t -> "builtin".equals(t.kind())
                && "call_tool".equals(t.invocationMode()) && "echo".equals(t.name())));
        assertTrue(tools.stream().anyMatch(t -> "mcp".equals(t.kind())
                && "call_tool".equals(t.invocationMode()) && "weather".equals(t.name())
                && Long.valueOf(1L).equals(t.serverId())));
        assertTrue(tools.stream().anyMatch(t -> "skill".equals(t.kind())
                && "inject_prompt".equals(t.invocationMode()) && Long.valueOf(5L).equals(t.skillId())),
                "Skill 只能作为提示注入，不得伪装成可执行工具");
        assertEquals("PUBLISHED", skills.lastStatus, "目录只收录已发布 Skill");
    }

    @Test
    void mcpToolsAreScopedToOwningUser() {
        McpServerManager manager = managerWithWeatherFor(7L);
        FakeSkillService skills = new FakeSkillService();

        List<ToolDiscoveryService.DiscoveredTool> others = service(manager, skills).discover(8L);

        assertTrue(others.stream().noneMatch(t -> "mcp".equals(t.kind())), "其他用户不得看到他人 MCP 工具");
        assertEquals(1, others.size(), "其他用户只应看到内置工具");
    }
}
