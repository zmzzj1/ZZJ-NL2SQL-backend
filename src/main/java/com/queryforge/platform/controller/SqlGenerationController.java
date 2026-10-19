package com.queryforge.platform.controller;

import com.queryforge.platform.config.RequestIdFilter;
import com.queryforge.platform.dto.GenerateSqlRequest;
import com.queryforge.platform.security.AppUserPrincipal;
import com.queryforge.platform.service.SqlGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 契约 4.2：POST /api/v1/sql/generate/stream。
 */
@RestController
@RequestMapping("/api/v1/sql")
public class SqlGenerationController {

    private static final MediaType EVENT_STREAM = MediaType.parseMediaType("text/event-stream;charset=UTF-8");

    private final SqlGenerationService service;

    public SqlGenerationController(SqlGenerationService service) {
        this.service = service;
    }

    @PostMapping("/generate/stream")
    public ResponseEntity<StreamingResponseBody> generate(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody GenerateSqlRequest request,
            HttpServletRequest httpRequest) {
        String requestId = RequestIdFilter.current(httpRequest);
        SqlGenerationService.PreparedGeneration prepared = service.prepare(request, principal.id(), requestId);

        StreamingResponseBody body = outputStream -> service.writeStream(prepared, outputStream);
        return ResponseEntity.ok()
                .contentType(EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /** 客户端取消：只把本人仍在生成中的消息标记为 CANCELED。 */
    @PostMapping("/generate/{requestId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable String requestId) {
        service.cancel(principal.id(), requestId);
    }
}
