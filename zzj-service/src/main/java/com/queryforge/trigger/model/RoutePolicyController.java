package com.queryforge.trigger.model;

import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.domain.model.ModelEntities.RoutePolicyVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.queryforge.application.model.AdminGuard;
import com.queryforge.application.model.RoutePolicyService;

/** O2 路由策略管理。全部要求平台管理员。 */
@RestController
@RequestMapping("/api/v1/model/policies")
public class RoutePolicyController {

    private final RoutePolicyService service;
    private final AdminGuard adminGuard;

    public RoutePolicyController(RoutePolicyService service, AdminGuard adminGuard) {
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @PostMapping
    public RoutePolicyVersion saveDraft(@AuthenticationPrincipal AppUserPrincipal principal,
                                        @Valid @RequestBody SavePolicyRequest request) {
        adminGuard.requireAdmin(principal);
        return service.saveDraft(principal.id(), request.policyId(), request.strategy(), request.candidates(),
                request.fallbackChain(), request.weights(), request.affinityTtlSeconds());
    }

    @PostMapping("/{versionId}/publish")
    public RoutePolicyVersion publish(@AuthenticationPrincipal AppUserPrincipal principal,
                                      @PathVariable Long versionId) {
        adminGuard.requireAdmin(principal);
        return service.publish(principal.id(), versionId);
    }

    @PostMapping("/{versionId}/rollback")
    public RoutePolicyVersion rollback(@AuthenticationPrincipal AppUserPrincipal principal,
                                       @PathVariable Long versionId) {
        adminGuard.requireAdmin(principal);
        return service.rollback(principal.id(), versionId);
    }

    @GetMapping("/{policyId}/versions")
    public List<RoutePolicyVersion> versions(@AuthenticationPrincipal AppUserPrincipal principal,
                                             @PathVariable Long policyId) {
        adminGuard.requireAdmin(principal);
        return service.listVersions(policyId);
    }

    public record SavePolicyRequest(
            @NotNull Long policyId,
            @NotBlank String strategy,
            @NotEmpty List<Long> candidates,
            List<Long> fallbackChain,
            List<Double> weights,
            int affinityTtlSeconds) {
    }
}
