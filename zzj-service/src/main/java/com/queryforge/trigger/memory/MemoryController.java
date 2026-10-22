package com.queryforge.trigger.memory;

import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.application.memory.MemoryService.Confidence;
import com.queryforge.application.memory.MemoryService.MemoryType;
import com.queryforge.application.memory.MemoryService.Scope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
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
import com.queryforge.application.memory.MemoryService;
import com.queryforge.domain.memory.LongTermMemory;

/**
 * P8 长期 Memory 资源：创建、检索、修改、停用、删除。
 * 身份只来自认证上下文；工作空间记忆按成员权限。
 */
@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {

    private final MemoryService service;

    public MemoryController(MemoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<MemoryResponse> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) MemoryType type) {
        return service.list(principal.id(), type).stream().map(MemoryResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryResponse create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody CreateMemoryRequest request) {
        var memory = service.create(principal.id(), request.workspaceId(), request.type(), request.scope(),
                request.title(), request.content(), request.sourceRef(), request.confidence(), request.expiresAt());
        return MemoryResponse.from(memory);
    }

    @GetMapping("/{id}")
    public MemoryResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        return MemoryResponse.from(service.detail(principal.id(), id));
    }

    @PatchMapping("/{id}")
    public MemoryResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateMemoryRequest request) {
        return MemoryResponse.from(service.update(principal.id(), id,
                request.title(), request.content(), request.confidence()));
    }

    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        service.disable(principal.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long id) {
        service.delete(principal.id(), id);
    }

    @PostMapping("/search")
    public SearchResponse search(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody SearchRequest request) {
        List<LongTermMemory> memories = service.search(principal.id(), request.workspaceId(),
                request.scope(), request.query());
        return new SearchResponse(memories.stream().map(MemoryResponse::from).toList());
    }

    // ---- DTO ----

    public record CreateMemoryRequest(
            @NotNull MemoryType type,
            @NotNull Scope scope,
            Long workspaceId,
            @NotBlank String title,
            @NotBlank String content,
            String sourceRef,
            Confidence confidence,
            LocalDateTime expiresAt
    ) {
    }

    public record UpdateMemoryRequest(
            String title,
            String content,
            Confidence confidence
    ) {
    }

    public record SearchRequest(
            @NotBlank String query,
            Long workspaceId,
            @NotNull Scope scope
    ) {
    }

    public record SearchResponse(List<MemoryResponse> items) {
    }

    public record MemoryResponse(
            Long id, Long ownerId, Long workspaceId, String memoryType, String scope,
            String title, String content, String sourceRef, String confidence, String status,
            String expiresAt, String createdAt, String updatedAt
    ) {
        static MemoryResponse from(LongTermMemory memory) {
            return new MemoryResponse(
                    memory.id(), memory.ownerId(), memory.workspaceId(), memory.memoryType(), memory.scope(),
                    memory.title(), memory.content(), memory.sourceRef(), memory.confidence(), memory.status(),
                    memory.expiresAt() == null ? null : memory.expiresAt().toString(),
                    memory.createdAt().toString(), memory.updatedAt().toString());
        }
    }
}
