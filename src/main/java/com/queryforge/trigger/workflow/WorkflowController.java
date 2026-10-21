package com.queryforge.trigger.workflow;

import com.queryforge.application.workflow.PreparedApproval;
import com.queryforge.application.workflow.PreparedRun;
import com.queryforge.application.workflow.WorkflowDefinitionService;
import com.queryforge.application.workflow.WorkflowRunService;
import com.queryforge.domain.identity.AppUserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.queryforge.domain.workflow.StartWorkflowRunRequest;
import com.queryforge.domain.workflow.WorkflowRequest;
import com.queryforge.domain.workflow.WorkflowResponse;
import com.queryforge.domain.workflow.WorkflowRunResponse;

/**
 * X5/X6 工作流端点：DAG 定义 CRUD + 运行（SSE）+ 审批 + 取消 + 恢复。
 * SQL 节点停在等待审批处，批准后才实际查询（复用既有安全链）。
 * 定义管理走 {@link WorkflowDefinitionService}，运行编排走 {@link WorkflowRunService}。
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final MediaType EVENT_STREAM = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final WorkflowDefinitionService definitionService;
    private final WorkflowRunService runService;

    public WorkflowController(WorkflowDefinitionService definitionService, WorkflowRunService runService) {
        this.definitionService = definitionService;
        this.runService = runService;
    }

    @PostMapping
    public WorkflowResponse create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody WorkflowRequest request) {
        return definitionService.create(principal.id(), request);
    }

    @GetMapping
    public List<WorkflowResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return definitionService.list(principal.id());
    }

    @GetMapping("/{id}")
    public WorkflowResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return definitionService.detail(principal.id(), id);
    }

    @PutMapping("/{id}")
    public WorkflowResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id,
            @RequestParam int revision,
            @Valid @RequestBody WorkflowRequest request) {
        return definitionService.update(principal.id(), id, revision, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        definitionService.delete(principal.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/runs/stream")
    public ResponseEntity<StreamingResponseBody> run(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody StartWorkflowRunRequest request) {
        PreparedRun prepared = runService.prepareRun(principal.id(), id, request);
        return sse(outputStream -> runService.startRun(prepared, outputStream));
    }

    @PostMapping("/runs/{runId}/nodes/{nodeId}/approve/stream")
    public ResponseEntity<StreamingResponseBody> approve(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId,
            @PathVariable String nodeId) {
        PreparedApproval prepared = runService.prepareApproval(principal.id(), runId, nodeId);
        return sse(outputStream -> runService.executeApproval(prepared, outputStream));
    }

    @GetMapping("/runs/{runId}")
    public WorkflowRunResponse runDetail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId) {
        return runService.runDetail(principal.id(), runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId) {
        runService.cancel(principal.id(), runId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<StreamingResponseBody> sse(StreamingResponseBody body) {
        return ResponseEntity.ok()
                .contentType(EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
