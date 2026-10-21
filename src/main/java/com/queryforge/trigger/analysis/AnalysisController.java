package com.queryforge.trigger.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.identity.AppUserPrincipal;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.queryforge.infrastructure.web.SseEventWriter;
import com.queryforge.application.analysis.AnalysisService;
import com.queryforge.domain.analysis.AnalysisRunResponse;
import com.queryforge.domain.analysis.AnalysisStreamEvents;
import com.queryforge.domain.analysis.CreateAnalysisRunRequest;

/**
 * X2 Python 隔离分析端点。POST /runs/stream 走 SSE（analysis.started/completed/failed）。
 * 输入必须引用本次真实查询历史，服务端重新执行取真实结果，绝不接受前端伪造数据。
 */
@RestController
@RequestMapping("/api/v1/analysis/runs")
public class AnalysisController {

    private static final MediaType EVENT_STREAM = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final AnalysisService service;
    private final ObjectMapper objectMapper;

    public AnalysisController(AnalysisService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/stream")
    public ResponseEntity<StreamingResponseBody> create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateAnalysisRunRequest request) {
        return sse(outputStream -> {
            SseEventWriter writer = new SseEventWriter(outputStream, objectMapper);
            try {
                AnalysisRunResponse response = service.create(principal.id(), request);
                writer.event("analysis.started", new AnalysisStreamEvents.AnalysisStarted(response.id(), response.engine()));
                writer.event("analysis.completed", new AnalysisStreamEvents.AnalysisCompleted(
                        response.id(), response.chartType(), response.inputRowCount()));
            } catch (Exception ex) {
                String code = ex instanceof com.queryforge.shared.error.ApiException api
                        ? api.getErrorCode().name()
                        : "ANALYSIS_EXECUTION_ERROR";
                writer.event("analysis.failed", new AnalysisStreamEvents.AnalysisFailed(null, code, safeMessage(ex)));
            }
        });
    }

    @GetMapping("/{id}")
    public AnalysisRunResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return service.detail(principal.id(), id);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        service.cancel(principal.id(), id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<StreamingResponseBody> sse(StreamingResponseBody body) {
        return ResponseEntity.ok()
                .contentType(EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "分析执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
