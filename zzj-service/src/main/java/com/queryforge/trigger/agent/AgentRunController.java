package com.queryforge.trigger.agent;

import com.queryforge.domain.agent.AgentRunResponse;
import com.queryforge.domain.agent.AgentRunSummaryResponse;
import com.queryforge.domain.agent.ApproveAgentStepRequest;
import com.queryforge.domain.agent.CreateAgentRunRequest;
import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.application.agent.AgentRunService;
import com.queryforge.application.agent.PreparedApproval;
import com.queryforge.application.agent.PreparedRun;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * P4 契约 4.1：单 Agent 运行、确认、拒绝、取消与恢复。
 * 未确认的 SQL 不会执行；确认请求按 runId + stepId + sqlVersion 幂等。
 */
@RestController
@RequestMapping("/api/v1/agent/runs")
public class AgentRunController {

    private static final MediaType EVENT_STREAM = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final AgentRunService service;

    public AgentRunController(AgentRunService service) {
        this.service = service;
    }

    @PostMapping("/stream")
    public ResponseEntity<StreamingResponseBody> start(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateAgentRunRequest request) {
        PreparedRun prepared = service.prepareStart(principal.id(), request);
        return sse(outputStream -> service.startRun(prepared, outputStream));
    }

    @PostMapping("/{runId}/steps/{stepId}/approve/stream")
    public ResponseEntity<StreamingResponseBody> approve(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId,
            @PathVariable Long stepId,
            @Valid @RequestBody ApproveAgentStepRequest request) {
        PreparedApproval prepared =
                service.prepareApproval(principal.id(), runId, stepId, request);
        return sse(outputStream -> service.executeApproval(prepared, outputStream));
    }

    @PostMapping("/{runId}/steps/{stepId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId,
            @PathVariable Long stepId) {
        service.reject(principal.id(), runId, stepId);
    }

    @PostMapping("/{runId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId) {
        service.cancel(principal.id(), runId);
    }

    @GetMapping("/{runId}")
    public AgentRunResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long runId) {
        return service.detail(principal.id(), runId);
    }

    @GetMapping
    public List<AgentRunSummaryResponse> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam Long conversationId) {
        return service.listByConversation(principal.id(), conversationId);
    }

    /** 会话恢复时取最近一次运行；没有运行时返回 204。 */
    @GetMapping("/latest")
    public ResponseEntity<AgentRunResponse> latest(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam Long conversationId) {
        return service.latest(principal.id(), conversationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static ResponseEntity<StreamingResponseBody> sse(StreamingResponseBody body) {
        return ResponseEntity.ok()
                .contentType(EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
