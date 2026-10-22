package com.queryforge.trigger.query;

import com.queryforge.shared.pagination.PageResponse;
import com.queryforge.domain.query.QueryHistoryResponse;
import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.application.query.QueryHistoryService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.queryforge.application.query.CsvExportService;

/**
 * P3 契约 3.3：真实执行历史。记录只能由后端执行流程写入。
 */
@RestController
@RequestMapping("/api/v1/query-history")
public class QueryHistoryController {

    private final QueryHistoryService service;
    private final CsvExportService exportService;

    public QueryHistoryController(QueryHistoryService service, CsvExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    @GetMapping
    public PageResponse<QueryHistoryResponse> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Long connectionId) {
        return service.list(principal.id(), limit, cursor, connectionId);
    }

    @GetMapping("/{id}")
    public QueryHistoryResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return service.detail(principal.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        service.delete(principal.id(), id);
    }

    /** 导出成功执行结果的 CSV：按“重新审批的再次查询”重新鉴权 + 重新执行，不缓存结果快照。 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        CsvExportService.CsvExport export = exportService.export(principal.id(), id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.filename()).build().toString())
                .body(export.bytes());
    }
}
