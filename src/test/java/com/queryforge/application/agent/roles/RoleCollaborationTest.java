package com.queryforge.application.agent.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.domain.analysis.CreateAnalysisRunRequest;
import com.queryforge.application.report.ReportService;
import com.queryforge.domain.report.CreateReportRequest;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.application.tool.ToolRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.agent.roles.RoleDisabledException;
import com.queryforge.domain.agent.roles.RoleProperties;
import com.queryforge.domain.agent.roles.RoleRegistry;
import com.queryforge.domain.agent.roles.ToolNotAllowedException;

class RoleCollaborationTest {

    @Test
    void assigner_mapsObjectiveToResponsibleRole() {
        RoleAssigner assigner = new RoleAssigner();
        assertThat(assigner.assign("生成近半年趋势报告")).isEqualTo(AgentRole.REPORT_ASSISTANT);
        assertThat(assigner.assign("对结果做相关性分析和可视化")).isEqualTo(AgentRole.CODE_ANALYST);
        assertThat(assigner.assign("查询本月销售额前 10 的订单")).isEqualTo(AgentRole.DATA_ANALYST);
        assertThat(assigner.assign("统计各区域销量")).isEqualTo(AgentRole.DATA_ANALYST);
        assertThat(assigner.assign("")).isEqualTo(AgentRole.DATA_ANALYST);
    }

    @Test
    void registry_rejectsDisabledRole() {
        RoleRegistry registry = new RoleRegistry(new RoleProperties(
                Map.of(AgentRole.CODE_ANALYST, false), Map.of()));
        assertThat(registry.isEnabled(AgentRole.CODE_ANALYST)).isFalse();
        assertThatThrownBy(() -> registry.requireAllowed(AgentRole.CODE_ANALYST, "builtin_analyzer"))
                .isInstanceOf(RoleDisabledException.class);
    }

    @Test
    void registry_rejectsToolNotInWhitelist() {
        RoleRegistry registry = new RoleRegistry(new RoleProperties(Map.of(), Map.of()));
        // DATA_ANALYST 仅允许 sql_guard，不允许 mcp
        assertThatThrownBy(() -> registry.requireAllowed(AgentRole.DATA_ANALYST, "mcp"))
                .isInstanceOf(ToolNotAllowedException.class);
        // TOOL_ASSISTANT 允许 mcp
        registry.requireAllowed(AgentRole.TOOL_ASSISTANT, "mcp");
    }

    @Test
    void codeAnalyst_rejectsWhenRoleDisabled() {
        RoleRegistry disabled = new RoleRegistry(new RoleProperties(
                Map.of(AgentRole.CODE_ANALYST, false), Map.of()));
        AnalysisService analysis = mock(AnalysisService.class);
        CodeAnalystAgent agent = new CodeAnalystAgent(disabled, analysis);

        CreateAnalysisRunRequest req = new CreateAnalysisRunRequest(1L, null, "bar");
        assertThatThrownBy(() -> agent.analyze(5L, req)).isInstanceOf(RoleDisabledException.class);
        // 未委派到下游
        verify(analysis, org.mockito.Mockito.never()).create(any(), any());
    }

    @Test
    void codeAnalyst_delegatesWhenEnabled() {
        RoleRegistry enabled = new RoleRegistry(new RoleProperties(Map.of(), Map.of()));
        AnalysisService analysis = mock(AnalysisService.class);
        when(analysis.create(eq(5L), any())).thenReturn(null);
        CodeAnalystAgent agent = new CodeAnalystAgent(enabled, analysis);

        CreateAnalysisRunRequest req = new CreateAnalysisRunRequest(1L, null, "bar");
        agent.analyze(5L, req);
        verify(analysis).create(eq(5L), eq(req));
    }

    @Test
    void toolAssistant_rejectsUnknownToolAndDelegatesWhenAllowed() {
        RoleRegistry enabled = new RoleRegistry(new RoleProperties(Map.of(), Map.of()));
        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.invoke(any(), any())).thenReturn(null);
        ToolAssistantAgent agent = new ToolAssistantAgent(enabled, tools);

        ToolInvocation invocation = new ToolInvocation(5L, "mcp", 1L, 9L, 2L, null, null);
        agent.invoke(invocation, Map.of());
        verify(tools).invoke(eq(invocation), any());
    }

    @Test
    void reportAssistant_delegatesWhenEnabled() {
        RoleRegistry enabled = new RoleRegistry(new RoleProperties(Map.of(), Map.of()));
        ReportService reports = mock(ReportService.class);
        when(reports.create(eq(5L), any())).thenReturn(null);
        ReportAssistantAgent agent = new ReportAssistantAgent(enabled, reports);

        CreateReportRequest req = new CreateReportRequest(1L, "报告");
        agent.create(5L, req);
        verify(reports).create(eq(5L), eq(req));
    }

    @Test
    void defaultContracts_allEnabled() {
        RoleRegistry registry = new RoleRegistry(new RoleProperties(Map.of(), Map.of()));
        for (AgentRole role : AgentRole.values()) {
            assertThat(registry.isEnabled(role)).isTrue();
        }
    }
}
