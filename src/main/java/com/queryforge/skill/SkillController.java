package com.queryforge.skill;

import com.queryforge.identity.AppUserPrincipal;
import jakarta.validation.Valid;
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

/** Skill Registry 资源：CRUD、版本发布、停用。 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillService service;

    public SkillController(SkillService service) {
        this.service = service;
    }

    @GetMapping
    public List<SkillService.SkillResponse> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) String status) {
        return service.list(principal.id(), status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SkillService.SkillResponse create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody SkillService.SkillRequest request) {
        return service.create(principal.id(), request);
    }

    @GetMapping("/{skillId}")
    public SkillService.SkillResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long skillId) {
        return service.detail(principal.id(), skillId);
    }

    @PatchMapping("/{skillId}")
    public SkillService.SkillResponse update(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long skillId,
            @RequestParam int expectedRevision,
            @Valid @RequestBody SkillService.SkillRequest request) {
        return service.update(principal.id(), skillId, expectedRevision, request);
    }

    @DeleteMapping("/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long skillId) {
        service.disable(principal.id(), skillId);
    }

    @GetMapping("/{skillId}/versions")
    public List<SkillService.SkillVersionResponse> versions(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long skillId) {
        return service.versions(principal.id(), skillId);
    }

    @PostMapping("/{skillId}/publish")
    public SkillService.SkillVersionResponse publish(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable Long skillId,
            @RequestParam int expectedRevision) {
        return service.publish(principal.id(), skillId, expectedRevision);
    }
}
