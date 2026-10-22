package com.queryforge.application.agent.roles;

import com.queryforge.application.agent.AuthorizedSqlInput;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.application.analysis.AnalysisService;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.roles.AgentRole;
import com.queryforge.domain.agent.roles.RoleRegistry;

/**
 * Code Analyst 角色（指南 §6）：在隔离环境对授权数据工件做 Python 分析。
 * 复用既有 {@link AnalysisService}（已强制“服务端重新执行取真实行”+ 沙箱不可用时拒绝回退）。
 * 本类再加角色门禁：角色或工具被禁用时真实拒绝（P1110/P1113）。
 */
@Service
public class CodeAnalystAgent {

    private final RoleRegistry registry;
    private final AnalysisService analysisService;

    public CodeAnalystAgent(RoleRegistry registry, AnalysisService analysisService) {
        this.registry = registry;
        this.analysisService = analysisService;
    }

    public AnalysisRunResponse analyze(Long userId, com.queryforge.domain.analysis.CreateAnalysisRunRequest request) {
        boolean usesSandbox = request.code() != null && !request.code().isBlank();
        registry.requireAllowed(AgentRole.CODE_ANALYST, usesSandbox ? "python_sandbox" : "builtin_analyzer");
        return analysisService.create(userId, request);
    }

    /**
     * P14-C：对一次已审批 SQL 的真实结果做隔离 Python 分析。
     * 入参必须是服务端审批执行或合规恢复流程产生的 {@link AuthorizedSqlInput}，
     * 浏览器无法传入虚构 rows；角色/工具门禁先于任何执行。
     */
    public AnalysisRunResponse analyzeApprovedResult(Long userId, AuthorizedSqlInput input,
                                                     String generatedPython, String chartType) {
        registry.requireAllowed(AgentRole.CODE_ANALYST, "python_sandbox");
        return analysisService.analyzeResult(
                userId,
                input.connectionId(),
                input.conversationId(),
                input.runId(),
                input.stepId(),
                input.queryHistoryId(),
                input.sourceSql(),
                input.result(),
                generatedPython,
                chartType);
    }
}
