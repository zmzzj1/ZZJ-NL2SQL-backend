package com.queryforge.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.agent.AgentSqlAssistant;
import com.queryforge.analysis.AnalysisRunResponse;
import com.queryforge.analysis.AnalysisService;
import com.queryforge.datasource.ConnectionService;
import com.queryforge.query.execution.ExecuteSqlRequest;
import com.queryforge.query.execution.QueryResultResponse;
import com.queryforge.query.execution.ReadOnlySqlGuard;
import com.queryforge.query.execution.SqlExecutionService;
import com.queryforge.query.execution.SseEventWriter;
import com.queryforge.report.ReportService;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.tool.ToolInvocation;
import com.queryforge.tool.ToolRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 工作流编排：DAG 校验 + 版本化存储 + 串行执行 + 审批暂停 + SSE。
 * SQL 节点复用 AgentSqlAssistant 生成 + ReadOnlySqlGuard 校验 + SqlExecutionService 执行，
 * 必须停在等待审批处；Python 复用 AnalysisService；Report 复用 ReportService；MCP 复用 ToolRegistry。
 * Workflow 不取代 Agent Run，而是组织可追踪的子任务（节点关联 analysisRunId/reportId）。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository repository;
    private final WorkflowGraphValidator validator;
    private final ObjectMapper objectMapper;
    private final ConnectionService connectionService;
    private final AgentSqlAssistant sqlAssistant;
    private final ReadOnlySqlGuard guard;
    private final SqlExecutionService executionService;
    private final AnalysisService analysisService;
    private final ReportService reportService;
    private final ToolRegistry toolRegistry;

    public WorkflowService(
            WorkflowRepository repository,
            WorkflowGraphValidator validator,
            ObjectMapper objectMapper,
            ConnectionService connectionService,
            AgentSqlAssistant sqlAssistant,
            ReadOnlySqlGuard guard,
            SqlExecutionService executionService,
            AnalysisService analysisService,
            ReportService reportService,
            ToolRegistry toolRegistry) {
        this.repository = repository;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.connectionService = connectionService;
        this.sqlAssistant = sqlAssistant;
        this.guard = guard;
        this.executionService = executionService;
        this.analysisService = analysisService;
        this.reportService = reportService;
        this.toolRegistry = toolRegistry;
    }

    public WorkflowResponse create(Long userId, WorkflowRequest request) {
        validator.validate(request.definition());
        String definition = writeJson(request.definition());
        Long id = repository.insertWorkflow(userId, request.name(), request.description(), definition);
        return toResponse(repository.findWorkflowOwned(id, userId).orElseThrow());
    }

    public WorkflowResponse update(Long userId, Long id, int expectedRevision, WorkflowRequest request) {
        validator.validate(request.definition());
        String definition = writeJson(request.definition());
        int updated = repository.updateWorkflow(id, userId, request.name(), request.description(),
                definition, expectedRevision);
        if (updated == 0) {
            throw new ApiException(ErrorCode.WORKFLOW_STATE_CONFLICT, HttpStatus.CONFLICT,
                    "工作流已被他人修改，请刷新后重试");
        }
        return toResponse(repository.findWorkflowOwned(id, userId).orElseThrow());
    }

    public List<WorkflowResponse> list(Long userId) {
        return repository.findWorkflows(userId).stream().map(this::toResponse).toList();
    }

    public WorkflowResponse detail(Long userId, Long id) {
        return toResponse(requireWorkflow(userId, id));
    }

    public void delete(Long userId, Long id) {
        if (repository.deleteWorkflow(id, userId) == 0) {
            throw new ApiException(ErrorCode.WORKFLOW_NOT_FOUND, HttpStatus.NOT_FOUND, "工作流不存在或无权访问");
        }
    }

    public PreparedRun prepareRun(Long userId, Long workflowId, StartWorkflowRunRequest request) {
        WorkflowRepository.Workflow workflow = requireWorkflow(userId, workflowId);
        WorkflowGraph graph = parseGraph(workflow.definition());
        validator.validate(graph);
        connectionService.summary(userId, request.connectionId());
        Long runId = repository.insertRun(userId, workflowId, workflow.revision(),
                request.conversationId(), request.connectionId());
        List<WorkflowGraph.Node> order = validator.topoOrder(graph);
        int sequence = 0;
        for (WorkflowGraph.Node node : order) {
            repository.insertNode(runId, node.id(), node.type(), sequence++);
        }
        return new PreparedRun(runId, userId, workflowId, request.connectionId(),
                request.conversationId(), request.userInput(), graph);
    }

    public void startRun(PreparedRun run, OutputStream outputStream) {
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        try {
            repository.updateRunStatus(run.runId(), WorkflowStatus.Run.RUNNING, null, null);
            writer.event("workflow.started", new WorkflowStreamEvents.WorkflowStarted(run.runId(), run.workflowId()));
            runToCompletion(run, writer, null);
        } catch (IOException ex) {
            log.info("workflow_client_closed runId={}", run.runId());
            repository.updateRunStatus(run.runId(), WorkflowStatus.Run.CANCELED, "CLIENT_CLOSED", "客户端断开");
        } catch (RuntimeException ex) {
            log.warn("workflow_failed runId={} message={}", run.runId(), ex.getMessage());
            repository.updateRunStatus(run.runId(), WorkflowStatus.Run.FAILED, "WORKFLOW_FAILED", safe(ex.getMessage()));
        }
    }

    public PreparedApproval prepareApproval(Long userId, Long runId, String nodeId) {
        WorkflowRepository.WorkflowRun run = repository.findRunOwned(runId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_RUN_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "工作流运行不存在或无权访问"));
        WorkflowRepository.Workflow workflow = requireWorkflow(userId, run.workflowId());
        WorkflowGraph graph = parseGraph(workflow.definition());
        if (run.status() != WorkflowStatus.Run.WAITING_APPROVAL) {
            throw new ApiException(ErrorCode.WORKFLOW_STATE_CONFLICT, HttpStatus.CONFLICT, "运行当前不在等待审批");
        }
        return new PreparedApproval(runId, userId, run.workflowId(), run.connectionId(),
                run.conversationId(), nodeId, graph);
    }

    public void executeApproval(PreparedApproval approval, OutputStream outputStream) {
        SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
        try {
            WorkflowRepository.WorkflowNode node = repository.findNodes(approval.runId()).stream()
                    .filter(item -> item.nodeId().equals(approval.nodeId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_STATE_CONFLICT, HttpStatus.CONFLICT,
                            "待审批节点不存在"));
            if (node.status() != WorkflowStatus.Node.WAITING_APPROVAL) {
                throw new ApiException(ErrorCode.WORKFLOW_STATE_CONFLICT, HttpStatus.CONFLICT, "该节点已处理");
            }
            String sql = node.outputSummary();
            QueryResultResponse result = executionService.execute(approval.userId(),
                    new ExecuteSqlRequest(approval.connectionId(), approval.conversationId(), sql));
            repository.updateNodeStatus(approval.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                    sql, null, null);
            writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(
                    approval.runId(), node.nodeId(), "执行成功，返回 " + result.rowCount() + " 行"));

            // 从批准节点之后继续推进
            PreparedRun continuation = new PreparedRun(approval.runId(), approval.userId(),
                    approval.workflowId(), approval.connectionId(), approval.conversationId(), "", approval.graph());
            repository.updateRunStatus(approval.runId(), WorkflowStatus.Run.RUNNING, null, null);
            runToCompletion(continuation, writer, node.nodeId());
        } catch (IOException ex) {
            log.info("workflow_approval_client_closed runId={}", approval.runId());
        } catch (ApiException ex) {
            try {
                writer.event("workflow.failed", new WorkflowStreamEvents.WorkflowFailed(
                        approval.runId(), ex.getErrorCode().name(), ex.getMessage()));
            } catch (IOException ignored) {
            }
            repository.updateRunStatus(approval.runId(), WorkflowStatus.Run.FAILED,
                    ex.getErrorCode().name(), safe(ex.getMessage()));
        }
    }

    /** 从指定节点（不含）继续串行执行；nodeId 为 null 表示从头开始。 */
    private void runToCompletion(PreparedRun run, SseEventWriter writer, String afterNodeId) throws IOException {
        List<WorkflowRepository.WorkflowNode> nodes = repository.findNodes(run.runId());
        boolean started = afterNodeId == null;
        for (WorkflowRepository.WorkflowNode node : nodes) {
            if (!started) {
                if (node.nodeId().equals(afterNodeId)) {
                    started = true;
                }
                continue;
            }
            if (node.status().terminal()) {
                continue;
            }
            boolean shouldPause = executeNode(run, node, writer);
            if (shouldPause) {
                return; // 停在审批，等待用户批准后继续
            }
        }
        repository.updateRunStatus(run.runId(), WorkflowStatus.Run.COMPLETED, null, null);
        writer.event("workflow.completed", new WorkflowStreamEvents.WorkflowCompleted(run.runId(), "工作流执行完成"));
    }

    /** 执行单个节点；返回 true 表示暂停等待审批。 */
    private boolean executeNode(PreparedRun run, WorkflowRepository.WorkflowNode node, SseEventWriter writer)
            throws IOException {
        writer.event("node.started", new WorkflowStreamEvents.NodeStarted(run.runId(), node.nodeId(), node.nodeType()));
        repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.RUNNING, null, null, null);
        try {
            return switch (node.nodeType()) {
                case "START" -> {
                    repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                            "开始", null, null);
                    writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(run.runId(), node.nodeId(), "开始"));
                    yield false;
                }
                case "END" -> {
                    repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                            "结束", null, null);
                    writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(run.runId(), node.nodeId(), "结束"));
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
            writer.event("node.failed", new WorkflowStreamEvents.NodeFailed(run.runId(), node.nodeId(),
                    ex.getErrorCode().name(), ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.FAILED,
                    null, "WORKFLOW_NODE_FAILED", safe(ex.getMessage()));
            writer.event("node.failed", new WorkflowStreamEvents.NodeFailed(run.runId(), node.nodeId(),
                    "WORKFLOW_NODE_FAILED", safe(ex.getMessage())));
            throw ex;
        }
    }

    /** SQL 节点：生成 SQL → Guard 校验 → 暂停等审批（output_summary 存 SQL 文本）。 */
    private boolean executeSqlNode(PreparedRun run, WorkflowRepository.WorkflowNode node, SseEventWriter writer)
            throws IOException {
        String schema = connectionService.schemaContext(run.userId(), run.connectionId());
        String sql = sqlAssistant.generateSql(run.userInput(), schema, "", "");
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

    private void executeAnalysisNode(PreparedRun run, WorkflowRepository.WorkflowNode node, SseEventWriter writer)
            throws IOException {
        QueryResultResponse result = findSqlResult(run);
        AnalysisRunResponse analysis = analysisService.analyzeResult(
                run.userId(), run.connectionId(), run.conversationId(), null, null, null, null,
                result, null, null);
        repository.updateNodeAnalysis(run.runId(), node.nodeId(), analysis.id());
        repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                "分析完成 #" + analysis.id(), null, null);
        writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(run.runId(), node.nodeId(),
                "分析完成 #" + analysis.id()));
    }

    private void executeReportNode(PreparedRun run, WorkflowRepository.WorkflowNode node, SseEventWriter writer)
            throws IOException {
        AnalysisRunResponse analysis = findAnalysisResult(run);
        var report = reportService.create(run.userId(),
                new com.queryforge.report.CreateReportRequest(analysis.id(), null));
        repository.updateNodeReport(run.runId(), node.nodeId(), report.id());
        repository.updateNodeStatus(run.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                "报告 #" + report.id(), null, null);
        writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(run.runId(), node.nodeId(),
                "报告 #" + report.id()));
    }

    private void executeToolNode(PreparedRun run, WorkflowRepository.WorkflowNode node, SseEventWriter writer)
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
        writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(run.runId(), node.nodeId(), "工具执行成功"));
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

    public WorkflowRunResponse runDetail(Long userId, Long runId) {
        WorkflowRepository.WorkflowRun run = repository.findRunOwned(runId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_RUN_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "工作流运行不存在或无权访问"));
        List<WorkflowRunResponse.NodeState> nodes = repository.findNodes(runId).stream()
                .map(node -> new WorkflowRunResponse.NodeState(node.nodeId(), node.nodeType(), node.sequence(),
                        node.status().name(), node.outputSummary(), node.errorCode(), node.errorMessage()))
                .toList();
        return new WorkflowRunResponse(run.id(), run.workflowId(), run.workflowRevision(), run.status().name(),
                run.errorCode(), run.errorMessage(), nodes, run.createdAt().toString(), run.updatedAt().toString());
    }

    public void cancel(Long userId, Long runId) {
        if (repository.findRunOwned(runId, userId).isEmpty()) {
            throw new ApiException(ErrorCode.WORKFLOW_RUN_NOT_FOUND, HttpStatus.NOT_FOUND, "工作流运行不存在或无权访问");
        }
        repository.cancelActiveRun(runId, userId);
    }

    private String database(PreparedRun run) {
        return connectionService.summary(run.userId(), run.connectionId()).database();
    }

    private WorkflowRepository.Workflow requireWorkflow(Long userId, Long id) {
        return repository.findWorkflowOwned(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "工作流不存在或无权访问"));
    }

    private WorkflowGraph parseGraph(String definition) {
        try {
            return objectMapper.readValue(definition, WorkflowGraph.class);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.WORKFLOW_INVALID_GRAPH, HttpStatus.UNPROCESSABLE_ENTITY, "工作流定义不是合法 JSON");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "工作流序列化失败");
        }
    }

    private WorkflowResponse toResponse(WorkflowRepository.Workflow workflow) {
        WorkflowGraph graph = parseGraph(workflow.definition());
        return new WorkflowResponse(workflow.id(), workflow.name(), workflow.description(),
                graph, workflow.revision(), workflow.status(), workflow.createdAt().toString(),
                workflow.updatedAt().toString());
    }

    private WorkflowGraph.Node findGraphNode(WorkflowGraph graph, String nodeId) {
        return graph.nodes().stream().filter(node -> node.id().equals(nodeId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_INVALID_GRAPH, HttpStatus.UNPROCESSABLE_ENTITY,
                        "节点不存在：" + nodeId));
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "工作流执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    public record PreparedRun(
            Long runId, Long userId, Long workflowId, Long connectionId, Long conversationId,
            String userInput, WorkflowGraph graph
    ) {
    }

    public record PreparedApproval(
            Long runId, Long userId, Long workflowId, Long connectionId, Long conversationId,
            String nodeId, WorkflowGraph graph
    ) {
    }
}
