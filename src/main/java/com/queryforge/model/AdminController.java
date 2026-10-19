package com.queryforge.model;

import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.model.UsageStatsService.FailureSummary;
import com.queryforge.model.UsageStatsService.ModelUsageSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * O5 平台管理：概览、模型用量、配额、审计。全部要求平台管理员。
 * 管理员看运行元数据与汇总指标，不返回个人聊天/私有 Memory/业务数据行。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminGuard adminGuard;
    private final UsageStatsService usageStats;
    private final QuotaService quotaService;
    private final ModelRepository repository;

    public AdminController(AdminGuard adminGuard, UsageStatsService usageStats, QuotaService quotaService,
                           ModelRepository repository) {
        this.adminGuard = adminGuard;
        this.usageStats = usageStats;
        this.quotaService = quotaService;
        this.repository = repository;
    }

    @GetMapping("/overview")
    public Overview overview(@AuthenticationPrincipal AppUserPrincipal principal) {
        adminGuard.requireAdmin(principal);
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long totalCalls = usageStats.totalCallsSince(since);
        List<ModelUsageSummary> usage = usageStats.modelUsageSummary(since);
        long failed = usage.stream().mapToLong(ModelUsageSummary::failedCalls).sum();
        return new Overview(totalCalls, failed, usage.size());
    }

    @GetMapping("/model-usage")
    public ModelUsageResponse modelUsage(@AuthenticationPrincipal AppUserPrincipal principal,
                                         @RequestParam(defaultValue = "60") int minutes) {
        adminGuard.requireAdmin(principal);
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        List<ModelUsageSummary> usage = usageStats.modelUsageSummary(since);
        List<FailureSummary> failures = usageStats.failureSummary(since);
        return new ModelUsageResponse(usage, failures, since);
    }

    @PostMapping("/quotas")
    public void upsertQuota(@AuthenticationPrincipal AppUserPrincipal principal,
                            @Valid @RequestBody QuotaRequest request) {
        adminGuard.requireAdmin(principal);
        quotaService.upsertQuota(principal.id(), request.scopeType(), request.scopeId(), request.requestsLimit(),
                request.concurrentLimit(), request.tokenLimit(), request.costLimit(), request.costCurrency());
    }

    @GetMapping("/audits")
    public List<AuditView> audits(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @RequestParam(defaultValue = "50") int limit) {
        adminGuard.requireAdmin(principal);
        int safeLimit = Math.min(limit, 200);
        return repository.listAudits(safeLimit).stream()
                .map(row -> new AuditView(row.id(), row.actorId(), row.action(), row.targetType(),
                        row.targetId(), row.detail(), row.result(), row.createdAt()))
                .toList();
    }

    public record Overview(long totalCalls, long failedCalls, int activeModels) {
    }

    public record ModelUsageResponse(List<ModelUsageSummary> models, List<FailureSummary> failures,
                                     LocalDateTime since) {
    }

    public record QuotaRequest(
            @NotBlank String scopeType,
            @NotNull Long scopeId,
            Integer requestsLimit,
            Integer concurrentLimit,
            Long tokenLimit,
            BigDecimal costLimit,
            String costCurrency) {
    }

    public record AuditView(Long id, Long actorId, String action, String targetType, Long targetId,
                            String detail, String result, LocalDateTime createdAt) {
    }
}
