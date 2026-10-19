package com.queryforge.workflow;

import com.queryforge.identity.AppUserPrincipal;
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

/**
 * X5/X6 工作流端点：DAG 定义 CRUD + 运行（SSE）+ 审批 + 取消 + 恢复。
 * SQL 节点停在等待审批处，批准后才实际查询（复用既有安全链）。
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final MediaType EVENT_STREAM = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping
    public WorkflowResponse create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody WorkflowRequest request) {
        return service.create(principal.id(), request);
    }

    @GetMapping
    public List<WorkflowResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.list(principal.id());
    }

    @GetMapping("/{id}")
    public WorkflowResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return service.detail(principal.id(), id);
    }

    @PutMapping("/{id}")
    public WorkflowResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id,
            @RequestParam int revision,
            @Valid @RequestBody WorkflowRequest request) {
        return service.update(principal.id(), id, revision, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        service.delete(principal.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/runs/stream")
    public ResponseEntity<StreamingResponseBody> run(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody StartWorkflowRunRequest request) {
        WorkflowService.PreparedRun prepared = service.prepareRun(principal.id(), id, request);
        return sse(outputStream -> service.startRun(prepared, outputStream));
    }

    @PostMapping("/runs/{runId}/nodes/{nodeId}/approve/stream")
    public ResponseEntity<StreamingResponseBody> approve(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId,
            @PathVariable String nodeId) {
        WorkflowService.PreparedApproval prepared = service.prepareApproval(principal.id(), runId, nodeId);
        return sse(outputStream -> service.executeApproval(prepared, outputStream));
    }

    @GetMapping("/runs/{runId}")
    public WorkflowRunResponse runDetail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId) {
        return service.runDetail(principal.id(), runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId) {
        service.cancel(principal.id(), runId);
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
