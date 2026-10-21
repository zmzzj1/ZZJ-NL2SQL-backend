package com.queryforge.application.agent.roles;

import com.queryforge.domain.report.CreateReportRequest;
import com.queryforge.domain.report.ReportResponse;
import com.queryforge.application.report.ReportService;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.agent.roles.RoleRegistry;

/**
 * Report Assistant 角色（指南 §6）：基于真实分析结果安全生成报告。
 * 复用既有 {@link ReportService}（只引用真实分析来源、HTML 严格净化、不虚构数字）。
 * 本类再加角色门禁：报告角色被禁用时真实拒绝（P1112/P1113）。
 */
@Service
public class ReportAssistantAgent {

    private final RoleRegistry registry;
    private final ReportService reportService;

    public ReportAssistantAgent(RoleRegistry registry, ReportService reportService) {
        this.registry = registry;
        this.reportService = reportService;
    }

    public ReportResponse create(Long userId, CreateReportRequest request) {
        registry.requireAllowed(AgentRole.REPORT_ASSISTANT, "report_service");
        return reportService.create(userId, request);
    }
}
