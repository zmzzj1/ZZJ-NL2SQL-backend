package com.queryforge.application.workflow;

import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.application.agent.AgentSqlAssistant;
import com.queryforge.application.skill.SkillGuidanceSelector;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.domain.query.ExecuteSqlRequest;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.application.query.SqlExecutionService;
import com.queryforge.application.report.ReportService;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.domain.tool.ToolInvocation;
import com.queryforge.application.tool.ToolRegistry;
import com.queryforge.domain.workflow.WorkflowGraph;
import com.queryforge.infrastructure.persistence.WorkflowRepository;
import com.queryforge.domain.workflow.WorkflowStatus;
import com.queryforge.domain.workflow.WorkflowStreamEvents;
import com.queryforge.infrastructure.web.SseEventWriter;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 工作流节点执行器：按节点类型分派动作（START/END/SQL_AGENT/PYTHON_ANALYSIS/REPORT/MCP_TOOL）。
 *
 * <p>SQL 节点复用 AgentSqlAssistant 生成 + ReadOnlySqlGuard 校验，必须停在等待审批处；
 * Python 复用 AnalysisService；Report 复用 ReportService；MCP 复用 ToolRegistry。
 * 节点失败即中止整个运行（状态落库 + 事件上报），由调用方转为运行终态。
 */
@Service
public class WorkflowNodeExecutor {

    private final WorkflowRepository repository;
    private final ConnectionService connectionService;
    private final AgentSqlAssistant sqlAssistant;
    private final ReadOnlySqlGuard guard;
    private final SqlExecutionService executionService;
    private final AnalysisService analysisService;
    private final ReportService reportService;
    private final ToolRegistry toolRegistry;
    private final SkillGuidanceSelector skillGuidanceSelector;

    public WorkflowNodeExecutor(
            WorkflowRepository repository,
            ConnectionService connectionService,
            AgentSqlAssistant sqlAssistant,
            ReadOnlySqlGuard guard,
            SqlExecutionService executionService,
            AnalysisService analysisService,
            ReportService reportService,
            ToolRegistry toolRegistry,
            SkillGuidanceSelector skillGuidanceSelector) {
        this.repository = repository;
        this.connectionService = connectionService;
        this.sqlAssistant = sqlAssistant;
        this.guard = guard;
        this.executionService = executionService;
        this.analysisService = analysisService;
        this.reportService = reportService;
        this.toolRegistry = toolRegistry;
        this.skillGuidanceSelector = skillGuidanceSelector;
    }

    /**
     * 审批通过后执行节点已生成并校验的 SQL（统一安全入口，带会话与连接校验）。
     * 执行失败按 ApiException/SqlExecutionException 上交，由协调器转为运行终态。
     */
    public QueryResultResponse executeApprovedSql(PreparedApproval approval, String sql) {
        return executionService.execute(approval.userId(),
                new ExecuteSqlRequest(approval.connectionId(), approval.conversationId(), sql));
    }

    /** 执行单个节点；返回 true 表示暂停等待审批。 */
    public boolean executeNode(PreparedRun run,
                               WorkflowRepository.WorkflowNode node,
                               SseEventWriter writer)
            throws IOException {
        writer.event("node.started", new WorkflowStreamEvents.NodeStarted(
                run.runId(), node.nodeId(), node.nodeType()));
        repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.RUNNING, null, null, null);
        try {
            return switch (node.nodeType()) {
                case "START" -> {
                    repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                            "开始", null, null);
                    writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(
                            run.runId(), node.nodeId(), "开始"));
                    yield false;
                }
                case "END" -> {
                    repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                            "结束", null, null);
                    writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(
                            run.runId(), node.nodeId(), "结束"));
                    yield false;
                }
                case "SQL_AGENT" -> executeSqlNode(run, node, writer);
                case "PYTHON_ANALYSIS" -> {
                    executeAnalysisNode(run, node, writer);
                    yield false;
                }
                case "REPORT" -> {
                    executeReportNode(run, node, writer);
                    yield false;
                }
                case "MCP_TOOL" -> {
                    executeToolNode(run, node, writer);
                    yield false;
                }
                default -> throw new ApiException(ErrorCode.WORKFLOW_INVALID_GRAPH, HttpStatus.UNPROCESSABLE_ENTITY,
                        "未知节点类型：" + node.nodeType());
            };
        } catch (ApiException ex) {
            repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.FAILED,
                    null, ex.getErrorCode().name(), ex.getMessage());
            writer.event("node.failed", new WorkflowStreamEvents.NodeFailed(
                    run.runId(), node.nodeId(), ex.getErrorCode().name(), ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.FAILED,
                    null, "WORKFLOW_NODE_FAILED", safe(ex.getMessage()));
            writer.event("node.failed", new WorkflowStreamEvents.NodeFailed(
                    run.runId(), node.nodeId(), "WORKFLOW_NODE_FAILED", safe(ex.getMessage())));
            throw ex;
        }
    }

    /** SQL 节点：生成 SQL → Guard 校验 → 暂停等审批（output_summary 存 SQL 文本）。 */
    private boolean executeSqlNode(PreparedRun run,
                                   WorkflowRepository.WorkflowNode node,
                                   SseEventWriter writer)
            throws IOException {
        String schema = connectionService.schemaContext(run.userId(), run.connectionId());
        String skillGuidance = selectSkillGuidance(run);
        String customPrompt = run.config() == null ? "" : run.config().systemPrompt();
        String sql = sqlAssistant.generateSql(run.userInput(), schema, "", "", customPrompt, skillGuidance);
        String validated = guard.validate(sql, database(run));
        if (validated.isBlank()) {
            throw new ApiException(ErrorCode.SQL_NOT_ALLOWED, HttpStatus.UNPROCESSABLE_ENTITY, "模型未生成可执行的 SQL");
        }
        repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.WAITING_APPROVAL,
                validated, null, null);
        repository.updateRunStatus(run.runId(), WorkflowStatus.Run.WAITING_APPROVAL, null, null);
        writer.event("node.waiting_approval", new WorkflowStreamEvents.NodeWaitingApproval(
                run.runId(), node.nodeId(), validated));
        return true;
    }

    /** 按任务问题选择绑定的已发布 Skill 并渲染指引（与 Agent 运行共用同一选择能力）。 */
    private String selectSkillGuidance(PreparedRun run) {
        return SkillGuidanceSelector.render(
                skillGuidanceSelector.select(run.config(), run.userInput()));
    }

    private void executeAnalysisNode(PreparedRun run,
                                     WorkflowRepository.WorkflowNode node,
                                     SseEventWriter writer)
            throws IOException {
        QueryResultResponse result = findSqlResult(run);
        AnalysisRunResponse analysis = analysisService.analyzeResult(
                run.userId(), run.connectionId(), run.conversationId(), null, null, null, null,
                result, null, null);
        repository.updateNodeAnalysis(run.runId(), node.nodeId(), analysis.id());
        repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                "分析完成 #" + analysis.id(), null, null);
        writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(
                run.runId(), node.nodeId(), "分析完成 #" + analysis.id()));
    }

    private void executeReportNode(PreparedRun run,
                                   WorkflowRepository.WorkflowNode node,
                                   SseEventWriter writer)
            throws IOException {
        AnalysisRunResponse analysis = findAnalysisResult(run);
        var report = reportService.create(run.userId(),
                new com.queryforge.domain.report.CreateReportRequest(analysis.id(), null));
        repository.updateNodeReport(run.runId(), node.nodeId(), report.id());
        repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                "报告 #" + report.id(), null, null);
        writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(
                run.runId(), node.nodeId(), "报告 #" + report.id()));
    }

    private void executeToolNode(PreparedRun run,
                                 WorkflowRepository.WorkflowNode node,
                                 SseEventWriter writer)
            throws IOException {
        WorkflowGraph.Node graphNode = findGraphNode(run.graph(), node.nodeId());
        String toolName = graphNode.config() == null ? null : graphNode.config().toolName();
        if (toolName == null || toolName.isBlank()) {
            throw new ApiException(ErrorCode.WORKFLOW_INVALID_GRAPH, HttpStatus.UNPROCESSABLE_ENTITY, "MCP 工具节点缺少工具名");
        }
        ToolInvocation invocation = new ToolInvocation(run.userId(), toolName, run.conversationId(),
                null, null, run.runId(), null);
        toolRegistry.invoke(invocation, Map.of());
        repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                "工具执行成功", null, null);
        writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(
                run.runId(), node.nodeId(), "工具执行成功"));
    }

    /** 从上一个已成功的 SQL 节点取 SQL 文本重新执行，得到真实受限结果。 */
    private QueryResultResponse findSqlResult(PreparedRun run) {
        for (WorkflowRepository.WorkflowNode node : repository.findNodes(run.runId())) {
            if ("SQL_AGENT".equals(node.nodeType()) && node.status() == WorkflowStatus.Node.SUCCEEDED) {
                String sql = node.outputSummary();
                if (sql != null && !sql.isBlank()) {
                    return executionService.execute(run.userId(),
                            new ExecuteSqlRequest(run.connectionId(), run.conversationId(), sql));
                }
            }
        }
        throw new ApiException(ErrorCode.WORKFLOW_STATE_CONFLICT, HttpStatus.CONFLICT, "缺少 SQL 节点的执行结果");
    }

    private AnalysisRunResponse findAnalysisResult(PreparedRun run) {
        for (WorkflowRepository.WorkflowNode node : repository.findNodes(run.runId())) {
            if ("PYTHON_ANALYSIS".equals(node.nodeType()) && node.status() == WorkflowStatus.Node.SUCCEEDED) {
                Long analysisRunId = node.analysisRunId();
                if (analysisRunId != null) {
                    return analysisService.detail(run.userId(), analysisRunId);
                }
            }
        }
        throw new ApiException(ErrorCode.WORKFLOW_STATE_CONFLICT, HttpStatus.CONFLICT, "缺少分析节点结果");
    }

    private WorkflowGraph.Node findGraphNode(WorkflowGraph graph, String nodeId) {
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_INVALID_GRAPH, HttpStatus.UNPROCESSABLE_ENTITY,
                        "节点不存在：" + nodeId));
    }

    private String database(PreparedRun run) {
        return connectionService.summary(run.userId(), run.connectionId()).database();
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "工作流执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
