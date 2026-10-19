package com.queryforge.conversation;

import com.queryforge.conversation.ConversationDetailResponse;
import com.queryforge.conversation.ConversationSummaryResponse;
import com.queryforge.conversation.CreateConversationRequest;
import com.queryforge.shared.pagination.PageResponse;
import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.conversation.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 契约 3.1：会话 CRUD。所有操作以当前登录用户为边界。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ConversationSummaryResponse> create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateConversationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.id(), request));
    }

    @GetMapping
    public PageResponse<ConversationSummaryResponse> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.list(principal.id(), limit, cursor);
    }

    @GetMapping("/{id}")
    public ConversationDetailResponse detail(
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
