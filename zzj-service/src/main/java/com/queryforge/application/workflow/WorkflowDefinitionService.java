package com.queryforge.application.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.domain.workflow.WorkflowGraph;
import com.queryforge.domain.workflow.WorkflowGraphValidator;
import com.queryforge.infrastructure.persistence.WorkflowRepository;
import com.queryforge.domain.workflow.WorkflowRequest;
import com.queryforge.domain.workflow.WorkflowResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 工作流定义管理：DAG 校验 + 版本化存储 + 乐观锁更新。
 * 只负责“定义”本身；运行编排见 {@link WorkflowRunService}。
 */
@Service
public class WorkflowDefinitionService {

    private final WorkflowRepository repository;
    private final WorkflowGraphValidator validator;
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionService(
            WorkflowRepository repository, WorkflowGraphValidator validator, ObjectMapper objectMapper) {
        this.repository = repository;
        this.validator = validator;
        this.objectMapper = objectMapper;
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

    public WorkflowRepository.Workflow requireWorkflow(Long userId, Long id) {
        return repository.findWorkflowOwned(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.WORKFLOW_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "工作流不存在或无权访问"));
    }

    public WorkflowGraph parseGraph(String definition) {
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
}
