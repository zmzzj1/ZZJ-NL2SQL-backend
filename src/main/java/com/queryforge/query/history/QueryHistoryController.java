package com.queryforge.query.history;

import com.queryforge.shared.pagination.PageResponse;
import com.queryforge.query.history.QueryHistoryResponse;
import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.query.history.QueryHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 契约 3.3：真实执行历史。记录只能由后端执行流程写入。
 */
@RestController
@RequestMapping("/api/v1/query-history")
public class QueryHistoryController {

    private final QueryHistoryService service;

    public QueryHistoryController(QueryHistoryService service) {
        this.service = service;
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
}
