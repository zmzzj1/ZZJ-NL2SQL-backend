package com.queryforge.agent.roles;

import com.queryforge.analysis.AnalysisRunResponse;
import com.queryforge.analysis.AnalysisService;
import com.queryforge.analysis.CreateAnalysisRunRequest;
import org.springframework.stereotype.Service;

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

    public AnalysisRunResponse analyze(Long userId, CreateAnalysisRunRequest request) {
        boolean usesSandbox = request.code() != null && !request.code().isBlank();
        registry.requireAllowed(AgentRole.CODE_ANALYST, usesSandbox ? "python_sandbox" : "builtin_analyzer");
        return analysisService.create(userId, request);
    }
}
