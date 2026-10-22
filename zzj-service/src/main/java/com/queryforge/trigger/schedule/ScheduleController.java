package com.queryforge.trigger.schedule;

import com.queryforge.domain.identity.AppUserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;
import com.queryforge.application.schedule.ScheduleService;
import com.queryforge.domain.schedule.CreateScheduleRequest;
import com.queryforge.domain.schedule.OccurrenceResponse;
import com.queryforge.domain.schedule.ScheduleResponse;
import com.queryforge.domain.schedule.UpdateScheduleRequest;

/**
 * 定时计划接口。计划/触发仅管理元数据，真正执行复用现有 Workflow Run。
 * SQL 审批不在此提供新接口，仍走 /api/v1/workflows/runs/{runId}/nodes/{nodeId}/approve/stream。
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public List<ScheduleResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.list(principal.id());
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateScheduleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.id(), request));
    }

    @GetMapping("/{id}")
    public ScheduleResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return service.detail(principal.id(), id);
    }

    @PatchMapping("/{id}")
    public ScheduleResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id,
            @RequestParam int revision,
            @RequestBody UpdateScheduleRequest request) {
        return service.update(principal.id(), id, revision, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        service.delete(principal.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/preview")
    public List<String> preview(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestBody PreviewRequest request) {
        return service.preview(principal.id(), request.cron(), request.timezone(), request.count());
    }

    @PostMapping("/{id}/trigger")
    public OccurrenceResponse trigger(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return service.triggerNow(principal.id(), id);
    }

    @GetMapping("/{id}/runs")
    public List<OccurrenceResponse> occurrences(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return service.listOccurrences(principal.id(), id);
    }

    public record PreviewRequest(String cron, String timezone, int count) {
    }
}
