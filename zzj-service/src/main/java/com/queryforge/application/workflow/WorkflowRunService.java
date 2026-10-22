package com.queryforge.application.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.application.agent.RuntimeConfigResolver;
import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.domain.workflow.StartWorkflowRunRequest;
import com.queryforge.domain.workflow.WorkflowGraph;
import com.queryforge.domain.workflow.WorkflowGraphValidator;
import com.queryforge.infrastructure.persistence.WorkflowRepository;
import com.queryforge.domain.workflow.WorkflowRunResponse;
import com.queryforge.domain.workflow.WorkflowStatus;
import com.queryforge.domain.workflow.WorkflowStreamEvents;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 工作流运行编排（用例层协调器）：Run 生命周期（创建/启动/审批/取消/查询）与 DAG 串行调度。
 *
 * <p>节点动作委托 {@link WorkflowNodeExecutor}，定义读写委托 {@link WorkflowDefinitionService}；
 * Workflow 不取代 Agent Run，而是组织可追踪的子任务（节点关联 analysisRunId/reportId）。
 * SQL 节点必须停在等待审批处，批准后才实际查询（复用既有安全链）。
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);

    private final WorkflowRepository repository;
    private final WorkflowGraphValidator validator;
    private final ObjectMapper objectMapper;
    private final ConnectionService connectionService;
    private final WorkflowDefinitionService definitionService;
    private final WorkflowNodeExecutor nodeExecutor;
    private final RuntimeConfigResolver configResolver;

    public WorkflowRunService(
            WorkflowRepository repository,
            WorkflowGraphValidator validator,
            ObjectMapper objectMapper,
            ConnectionService connectionService,
            WorkflowDefinitionService definitionService,
            WorkflowNodeExecutor nodeExecutor,
            RuntimeConfigResolver configResolver) {
        this.repository = repository;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.connectionService = connectionService;
        this.definitionService = definitionService;
        this.nodeExecutor = nodeExecutor;
        this.configResolver = configResolver;
    }

    public PreparedRun prepareRun(Long userId, Long workflowId, StartWorkflowRunRequest request) {
        WorkflowRepository.Workflow workflow = definitionService.requireWorkflow(userId, workflowId);
        WorkflowGraph graph = definitionService.parseGraph(workflow.definition());
        validator.validate(graph);
        connectionService.summary(userId, request.connectionId());
        ResolvedAgentConfig config =
                configResolver.resolveForRun(userId, request.agentId(), request.agentVersionId());
        Long runId = repository.insertRun(userId, workflowId, workflow.revision(),
                request.conversationId(), request.connectionId(), config.agentId(), config.agentVersionId());
        List<WorkflowGraph.Node> order = validator.topoOrder(graph);
        int sequence = 0;
        for (WorkflowGraph.Node node : order) {
            repository.insertNode(runId, node.id(), node.type(), sequence++);
        }
        return new PreparedRun(runId, userId, workflowId, request.connectionId(),
                request.conversationId(), request.userInput(), graph, config);
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
        WorkflowRepository.Workflow workflow = definitionService.requireWorkflow(userId, run.workflowId());
        WorkflowGraph graph = definitionService.parseGraph(workflow.definition());
        if (run.status() != WorkflowStatus.Run.WAITING_APPROVAL) {
            throw new ApiException(ErrorCode.WORKFLOW_STATE_CONFLICT, HttpStatus.CONFLICT, "运行当前不在等待审批");
        }
        ResolvedAgentConfig config = configResolver.resolveForRun(
                run.userId(), run.agentId(), run.agentVersionId());
        return new PreparedApproval(runId, userId, run.workflowId(), run.connectionId(),
                run.conversationId(), nodeId, graph, config);
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
            QueryResultResponse result = nodeExecutor.executeApprovedSql(approval, sql);
            repository.updateNodeStatus(approval.runId(), node.nodeId(), WorkflowStatus.Node.SUCCEEDED,
                    sql, null, null);
            writer.event("node.succeeded", new WorkflowStreamEvents.NodeSucceeded(
                    approval.runId(), node.nodeId(), "执行成功，返回 " + result.rowCount() + " 行"));

            // 从批准节点之后继续推进
            PreparedRun continuation = new PreparedRun(approval.runId(), approval.userId(),
                    approval.workflowId(), approval.connectionId(), approval.conversationId(), "", approval.graph(),
                    approval.config());
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
            boolean shouldPause = nodeExecutor.executeNode(run, node, writer);
            if (shouldPause) {
                return; // 停在审批，等待用户批准后继续
            }
        }
        repository.updateRunStatus(run.runId(), WorkflowStatus.Run.COMPLETED, null, null);
        writer.event("workflow.completed", new WorkflowStreamEvents.WorkflowCompleted(run.runId(), "工作流执行完成"));
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

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "工作流执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
