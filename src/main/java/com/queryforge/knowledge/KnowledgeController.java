package com.queryforge.knowledge;

import com.queryforge.knowledge.KnowledgeEntryRequest;
import com.queryforge.knowledge.KnowledgeEntryResponse;
import com.queryforge.knowledge.KnowledgeSearchRequest;
import com.queryforge.knowledge.KnowledgeSearchResponse;
import com.queryforge.knowledge.KnowledgeType;
import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * P5 契约 5.2：业务术语与审核 SQL 示例的最小 CRUD 与检索。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @PostMapping("/entries")
    public ResponseEntity<KnowledgeEntryResponse> create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody KnowledgeEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.id(), request));
    }

    @GetMapping("/entries")
    public List<KnowledgeEntryResponse> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) KnowledgeType sourceType) {
        return service.list(principal.id(), sourceType);
    }

    @PutMapping("/entries/{id}")
    public KnowledgeEntryResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeEntryRequest request) {
        return service.update(principal.id(), id, request);
    }

    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        service.delete(principal.id(), id);
    }

    @PostMapping("/search")
    public KnowledgeSearchResponse search(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody KnowledgeSearchRequest request) {
        return service.search(principal.id(), request.connectionId(), request.query());
    }
}
