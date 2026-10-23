package com.queryforge.trigger.studio;

import com.queryforge.application.agent.AgentRunService;
import com.queryforge.application.agent.PreparedRun;
import com.queryforge.domain.agent.CreateAgentRunRequest;
import com.queryforge.domain.identity.AppUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.queryforge.application.studio.StudioService;

/**
 * Agent Studio 资源：草稿管理、发布、版本历史、回滚、测试运行。
 * PATCH 需要 expectedRevision 乐观锁；身份只来自认证上下文。
 * 测试运行复用 AgentRunService（同一执行面），以 draft=true 读取草稿快照。
 */
@RestController
@RequestMapping("/api/v1/studio/agents")
public class StudioController {

    private static final MediaType EVENT_STREAM = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final StudioService service;
    private final AgentRunService agentRunService;

    public StudioController(StudioService service, AgentRunService agentRunService) {
        this.service = service;
        this.agentRunService = agentRunService;
    }

    @GetMapping
    public List<StudioService.AgentResponse> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) String status) {
        return service.list(principal.id(), status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StudioService.AgentResponse create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody StudioService.AgentRequest request) {
        return service.create(principal.id(), request);
    }

    @GetMapping("/{agentId}")
    public StudioService.AgentResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long agentId) {
        return service.detail(principal.id(), agentId);
    }

    @PatchMapping("/{agentId}")
    public StudioService.AgentResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long agentId,
            @RequestParam int expectedRevision,
            @Valid @RequestBody StudioService.AgentRequest request) {
        return service.update(principal.id(), agentId, expectedRevision, request);
    }

    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long agentId) {
        service.archive(principal.id(), agentId);
    }

    @GetMapping("/{agentId}/versions")
    public List<StudioService.AgentVersionResponse> versions(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long agentId) {
        return service.versions(principal.id(), agentId);
    }

    @PostMapping("/{agentId}/publish")
    public StudioService.AgentVersionResponse publish(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long agentId,
            @RequestParam int expectedRevision,
            @RequestBody(required = false) PublishRequest request) {
        return service.publish(principal.id(), agentId, expectedRevision,
                request == null ? null : request.versionNote());
    }

    @PostMapping("/{agentId}/rollback")
    public StudioService.AgentVersionResponse rollback(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long agentId,
            @RequestParam long sourceVersionId,
            @RequestParam int expectedRevision) {
        return service.rollback(principal.id(), agentId, sourceVersionId, expectedRevision);
    }

    /** Studio 测试运行：读取草稿快照，走真实 Agent Run/SSE（SQL 仍需人工审批）。 */
    @PostMapping("/{agentId}/test/stream")
    public ResponseEntity<StreamingResponseBody> test(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long agentId,
            @Valid @RequestBody TestRequest request) {
        // P14-D：Studio 测试运行沿用原有行为——跳过计划级审核，但 SQL 逐版本确认仍然生效。
        CreateAgentRunRequest runRequest = new CreateAgentRunRequest(
                request.conversationId(), request.connectionId(), request.userInput(), agentId, null, true, null, null,
                Boolean.TRUE);
        PreparedRun prepared = agentRunService.prepareStart(principal.id(), runRequest);
        return ResponseEntity.ok()
                .contentType(EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(outputStream -> agentRunService.startRun(prepared, outputStream));
    }

    public record PublishRequest(String versionNote) {
    }

    public record TestRequest(
            @NotNull Long conversationId,
            @NotNull Long connectionId,
            @NotBlank String userInput
    ) {
    }
}
