package com.queryforge.platform.controller;

import com.queryforge.platform.dto.ExecuteSqlRequest;
import com.queryforge.platform.dto.QueryResultResponse;
import com.queryforge.platform.service.SqlExecutionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约 4.3：POST /api/v1/sql/execute。只接受用户显式提交的 SQL。
 */
@RestController
@RequestMapping("/api/v1/sql")
public class SqlExecutionController {

    private final SqlExecutionService service;

    public SqlExecutionController(SqlExecutionService service) {
        this.service = service;
    }

    @PostMapping("/execute")
    public QueryResultResponse execute(@Valid @RequestBody ExecuteSqlRequest request) {
        return service.execute(request.sql());
    }
}
