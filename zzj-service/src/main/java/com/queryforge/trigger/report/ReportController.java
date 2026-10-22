package com.queryforge.trigger.report;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.queryforge.application.report.ReportService;
import com.queryforge.domain.report.CreateReportRequest;
import com.queryforge.domain.report.ReportResponse;

/**
 * X3 报告端点。preview 返回净化后的 HTML，前端放入沙箱 iframe + CSP，禁止脚本执行。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @PostMapping
    public ReportResponse create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateReportRequest request) {
        return service.create(principal.id(), request);
    }

    @GetMapping
    public List<ReportResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.list(principal.id());
    }

    @GetMapping("/{id}")
    public ReportResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return service.detail(principal.id(), id);
    }

    /** 预览：服务端已净化 + CSP + 沙箱 iframe；直接返回 HTML 供隔离渲染。 */
    @GetMapping(value = "/{id}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        ReportResponse report = service.detail(principal.id(), id);
        return ResponseEntity.ok()
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(report.htmlContent());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        service.delete(principal.id(), id);
        return ResponseEntity.noContent().build();
    }
}
