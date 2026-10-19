package com.queryforge.model;

import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.model.ModelEntities.Profile;
import com.queryforge.model.ModelEntities.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O1 模型供应商与能力档案管理。全部操作要求平台管理员。
 * 供应商列表/详情不回传 Key 密文与明文。
 */
@RestController
@RequestMapping("/api/v1/model")
public class ModelAdminController {

    private final ModelCatalogService catalog;
    private final AdminGuard adminGuard;

    public ModelAdminController(ModelCatalogService catalog, AdminGuard adminGuard) {
        this.catalog = catalog;
        this.adminGuard = adminGuard;
    }

    // ---- Provider ----

    @GetMapping("/providers")
    public List<ProviderView> listProviders(@AuthenticationPrincipal AppUserPrincipal principal) {
        adminGuard.requireAdmin(principal);
        return catalog.listProviders().stream().map(ProviderView::from).toList();
    }

    @PostMapping("/providers")
    public ProviderView createProvider(@AuthenticationPrincipal AppUserPrincipal principal,
                                       @Valid @RequestBody CreateProviderRequest request) {
        adminGuard.requireAdmin(principal);
        Provider provider = catalog.createProvider(principal.id(), request.name(), request.protocol(),
                request.baseUrl(), request.apiKey());
        return ProviderView.from(provider);
    }

    @PatchMapping("/providers/{id}")
    public ProviderView updateProvider(@AuthenticationPrincipal AppUserPrincipal principal,
                                       @PathVariable Long id, @Valid @RequestBody UpdateProviderRequest request) {
        adminGuard.requireAdmin(principal);
        Provider provider = catalog.updateProvider(principal.id(), id, request.name(), request.baseUrl(), request.enabled());
        return ProviderView.from(provider);
    }

    @PostMapping("/providers/{id}/rotate-key")
    public ProviderView rotateKey(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @PathVariable Long id, @Valid @RequestBody RotateKeyRequest request) {
        adminGuard.requireAdmin(principal);
        Provider provider = catalog.rotateKey(principal.id(), id, request.apiKey());
        return ProviderView.from(provider);
    }

    @PostMapping("/providers/{id}/test")
    public ModelCatalogService.TestResult testProvider(@AuthenticationPrincipal AppUserPrincipal principal,
                                                       @PathVariable Long id) {
        adminGuard.requireAdmin(principal);
        return catalog.testProvider(principal.id(), id);
    }

    // ---- Profile ----

    @GetMapping("/profiles")
    public List<Profile> listProfiles(@AuthenticationPrincipal AppUserPrincipal principal) {
        adminGuard.requireAdmin(principal);
        return catalog.listProfiles();
    }

    @PostMapping("/profiles")
    public Profile createProfile(@AuthenticationPrincipal AppUserPrincipal principal,
                                 @Valid @RequestBody CreateProfileRequest request) {
        adminGuard.requireAdmin(principal);
        return catalog.createProfile(principal.id(), request.providerId(), request.modelId(), request.modelType(),
                request.supportsStreaming(), request.supportsToolCalls(), request.supportsJson(),
                request.contextLimit(), request.outputLimit(), request.embeddingDim());
    }

    @PatchMapping("/profiles/{id}")
    public Profile updateProfile(@AuthenticationPrincipal AppUserPrincipal principal,
                                 @PathVariable Long id, @Valid @RequestBody UpdateProfileRequest request) {
        adminGuard.requireAdmin(principal);
        return catalog.updateProfile(principal.id(), id, request.supportsStreaming(), request.supportsToolCalls(),
                request.supportsJson(), request.contextLimit(), request.outputLimit(), request.status());
    }

    // ---- Grant ----

    @PostMapping("/profiles/{id}/grants")
    public void grant(@AuthenticationPrincipal AppUserPrincipal principal,
                      @PathVariable Long id, @Valid @RequestBody GrantRequest request) {
        adminGuard.requireAdmin(principal);
        catalog.grant(principal.id(), id, request.grantType(), request.granteeId());
    }

    @PostMapping("/profiles/{id}/revoke")
    public void revoke(@AuthenticationPrincipal AppUserPrincipal principal,
                       @PathVariable Long id, @Valid @RequestBody GrantRequest request) {
        adminGuard.requireAdmin(principal);
        catalog.revoke(principal.id(), id, request.grantType(), request.granteeId());
    }

    // ---- DTOs ----

    /** 供应商视图：不含 apiKeyCipher。 */
    public record ProviderView(Long id, String name, String protocol, String baseUrl, boolean enabled,
                               Long createdBy, String createdAt, String updatedAt) {
        static ProviderView from(Provider p) {
            return new ProviderView(p.id(), p.name(), p.protocol(), p.baseUrl(), p.enabled(), p.createdBy(),
                    String.valueOf(p.createdAt()), String.valueOf(p.updatedAt()));
        }
    }

    public record CreateProviderRequest(
            @NotBlank String name,
            @NotBlank String protocol,
            @NotBlank String baseUrl,
            @NotBlank String apiKey) {
    }

    public record UpdateProviderRequest(String name, String baseUrl, @NotNull Boolean enabled) {
    }

    public record RotateKeyRequest(@NotBlank String apiKey) {
    }

    public record CreateProfileRequest(
            @NotNull Long providerId,
            @NotBlank String modelId,
            @NotBlank String modelType,
            boolean supportsStreaming,
            boolean supportsToolCalls,
            boolean supportsJson,
            int contextLimit,
            int outputLimit,
            Integer embeddingDim) {
    }

    public record UpdateProfileRequest(
            boolean supportsStreaming,
            boolean supportsToolCalls,
            boolean supportsJson,
            int contextLimit,
            int outputLimit,
            @NotBlank String status) {
    }

    public record GrantRequest(@NotBlank String grantType, @NotNull Long granteeId) {
    }
}
