package com.queryforge.application.schedule;

import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.infrastructure.persistence.ScheduleRepository.OccurrenceRow;
import com.queryforge.infrastructure.persistence.ScheduleRepository.ScheduleRow;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.infrastructure.persistence.WorkflowRepository;
import com.queryforge.application.workspace.WorkspaceAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.application.agent.RuntimeConfigResolver;
import com.queryforge.domain.schedule.CreateScheduleRequest;
import com.queryforge.domain.schedule.CronSchedule;
import com.queryforge.domain.schedule.OccurrenceResponse;
import com.queryforge.domain.schedule.ScheduleProperties;
import com.queryforge.domain.schedule.ScheduleResponse;
import com.queryforge.domain.schedule.ScheduleStatus;
import com.queryforge.domain.schedule.UpdateScheduleRequest;
import com.queryforge.infrastructure.persistence.ScheduleRepository;

/**
 * 定时计划业务边界：CRUD + 启停 + 下次触发试算 + 手动触发。
 *
 * 安全要点：
 *  - 创建/触发前均重校验 workflow 归属与 ACTIVE、connection 归属、workspace 成员资格（若指定）、Agent 版本仍可解析。
 *  - 撤权后不能凭旧计划继续运行：触发时每次实时判权，不缓存。
 *  - 手动触发走与自动触发相同的 occurrence 幂等键，不绕过 SQL 人工审批。
 *  - 计划绑定固定 workflowRevision 与 connectionId，不可在更新中变更。
 */
@Service
public class ScheduleService {

    private final ScheduleRepository repository;
    private final WorkflowRepository workflowRepository;
    private final ConnectionService connectionService;
    private final WorkspaceAccessPolicy workspaceAccessPolicy;
    private final RuntimeConfigResolver configResolver;
    private final ScheduleAuthorizer authorizer;
    private final ScheduleTriggerRunner triggerRunner;
    private final Clock clock;
    private final ScheduleProperties properties;

    public ScheduleService(
            ScheduleRepository repository,
            WorkflowRepository workflowRepository,
            ConnectionService connectionService,
            WorkspaceAccessPolicy workspaceAccessPolicy,
            RuntimeConfigResolver configResolver,
            ScheduleAuthorizer authorizer,
            ScheduleTriggerRunner triggerRunner,
            Clock clock,
            ScheduleProperties properties) {
        this.repository = repository;
        this.workflowRepository = workflowRepository;
        this.connectionService = connectionService;
        this.workspaceAccessPolicy = workspaceAccessPolicy;
        this.configResolver = configResolver;
        this.authorizer = authorizer;
        this.triggerRunner = triggerRunner;
        this.clock = clock;
        this.properties = properties;
    }

    public ScheduleResponse create(long userId, CreateScheduleRequest request) {
        validateWorkflow(userId, request.workflowId());
        connectionService.summary(userId, request.connectionId());
        if (request.workspaceId() != null) {
            workspaceAccessPolicy.requireMember(request.workspaceId(), userId);
        }

        CronSchedule cron = CronSchedule.of(request.cron(), request.timezone());
        String misfire = normalizeMisfire(request.misfirePolicy());
        String overlap = normalizeOverlap(request.overlapPolicy());

        // 固定 Agent 版本：创建时解析为不可变 agentVersionId，触发时不随新发布漂移。
        Long resolvedAgentId = null;
        Long resolvedAgentVersionId = null;
        if (request.agentVersionId() != null) {
            ResolvedAgentConfig config = configResolver.resolvePublishedByVersion(userId, request.agentVersionId());
            resolvedAgentId = config.agentId();
            resolvedAgentVersionId = request.agentVersionId();
        } else if (request.agentId() != null) {
            ResolvedAgentConfig config = configResolver.resolvePublished(userId, request.agentId());
            resolvedAgentId = config.agentId();
            resolvedAgentVersionId = config.agentVersionId();
        }

        Instant now = clock.instant();
        Instant nextFire = cron.nextAfter(now);
        int workflowRevision = workflowRepository.findWorkflowOwned(request.workflowId(), userId).orElseThrow().revision();

        ScheduleRow row = new ScheduleRow(
                0, userId, request.workspaceId(), request.workflowId(), workflowRevision,
                request.connectionId(), null, request.userInput(), resolvedAgentId, resolvedAgentVersionId,
                cron.normalized(), cron.normalized(), request.timezone(), ScheduleStatus.Schedule.ACTIVE.name(),
                misfire, overlap, nextFire, null, 1, null, null);
        long id = repository.insertSchedule(row);
        return toResponse(repository.findOwned(id, userId).orElseThrow());
    }

    public ScheduleResponse update(long userId, long id, int expectedRevision, UpdateScheduleRequest request) {
        ScheduleRow existing = requireOwned(id, userId);

        String cron = existing.cronExpression();
        String cronNormalized = existing.cronNormalized();
        String timezone = existing.timezone();
        if (request.hasCron() || request.hasTimezone()) {
            CronSchedule parsed = CronSchedule.of(
                    request.hasCron() ? request.cron() : existing.cronExpression(),
                    request.hasTimezone() ? request.timezone() : existing.timezone());
            cron = parsed.normalized();
            cronNormalized = parsed.normalized();
            timezone = request.hasTimezone() ? request.timezone() : existing.timezone();
        }

        String status = existing.status();
        if (request.status() != null && !request.status().isBlank()) {
            status = normalizeStatus(request.status());
        }

        String misfire = request.misfirePolicy() == null || request.misfirePolicy().isBlank()
                ? existing.misfirePolicy() : normalizeMisfire(request.misfirePolicy());
        String overlap = request.overlapPolicy() == null || request.overlapPolicy().isBlank()
                ? existing.overlapPolicy() : normalizeOverlap(request.overlapPolicy());
        String userInput = request.userInput() == null ? existing.userInput() : request.userInput();

        Instant nextFire = existing.nextFireAt();
        if (request.hasCron() || request.hasTimezone()) {
            CronSchedule recompute = CronSchedule.of(cron, timezone);
            nextFire = recompute.nextAfter(clock.instant());
        }

        int updated = repository.updateSchedule(id, userId, expectedRevision, cron, cronNormalized,
                timezone, status, misfire, overlap, userInput, nextFire);
        if (updated == 0) {
            throw new ApiException(ErrorCode.SCHEDULE_CONFLICT, HttpStatus.CONFLICT, "计划已被他人修改，请刷新后重试");
        }
        return toResponse(repository.findOwned(id, userId).orElseThrow());
    }

    public void delete(long userId, long id) {
        if (repository.deleteOwned(id, userId) == 0) {
            throw new ApiException(ErrorCode.SCHEDULE_NOT_FOUND, HttpStatus.NOT_FOUND, "计划不存在或无权访问");
        }
    }

    public List<ScheduleResponse> list(long userId) {
        return repository.listOwned(userId).stream().map(this::toResponse).toList();
    }

    public ScheduleResponse detail(long userId, long id) {
        return toResponse(requireOwned(id, userId));
    }

    /** 试算未来 N 次触发时刻，返回 UTC 与本地时间。 */
    public List<String> preview(long userId, String cron, String timezone, int count) {
        CronSchedule parsed = CronSchedule.of(cron, timezone);
        int n = Math.min(Math.max(count, 1), 20);
        Instant cursor = clock.instant();
        List<String> result = new ArrayList<>(n);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
        for (int i = 0; i < n; i++) {
            Instant next = parsed.nextAfter(cursor);
            result.add(next.toString());
            cursor = next;
        }
        return result;
    }

    /** 手动触发：生成独立 occurrence 并同步执行到待审批/终态；幂等键复用 (scheduleId, fireAt, revision, attempt)。 */
    public OccurrenceResponse triggerNow(long userId, long scheduleId) {
        ScheduleRow schedule = requireOwned(scheduleId, userId);
        if (!ScheduleStatus.Schedule.valueOf(schedule.status()).enabled()) {
            throw new ApiException(ErrorCode.SCHEDULE_DISABLED, HttpStatus.CONFLICT, "计划已暂停，无法手动触发");
        }
        // 触发前重鉴权：撤权/资源失效立即生效。
        reauthorize(schedule);

        // 手动触发截断到秒：同一秒内重复触发幂等，返回同一 occurrence（对应指南「服务端幂等」语义）。
        Instant fireAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String fireAtLocal = fireAt.atZone(ZoneId.of(schedule.timezone()))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        boolean created = repository.insertOccurrenceIfAbsent(
                schedule.id(), fireAt, fireAtLocal, schedule.workflowRevision(),
                ScheduleStatus.TriggerSource.MANUAL.name());

        // 幂等：1 秒内重复手动触发返回同一 occurrence，不新建 Run。
        Optional<OccurrenceRow> existing = repository.findOccurrences(schedule.id(), 1).stream()
                .filter(o -> o.fireAtUtc() != null && o.fireAtUtc().equals(fireAt))
                .findFirst();

        if (!created && existing.isPresent()) {
            return toOccurrenceResponse(existing.get());
        }

        // 同步执行（手动触发等待结果返回更直观）；执行器内部做并发策略校验。
        OccurrenceRow occurrence = existing.orElseGet(() -> repository.findOccurrences(schedule.id(), 1).stream()
                .filter(o -> o.fireAtUtc() != null && o.fireAtUtc().equals(fireAt))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                        "触发记录创建失败")));
        triggerRunner.runOccurrence(occurrence);
        return toOccurrenceResponse(repository.findOccurrenceOwned(occurrence.id(), userId).orElseThrow());
    }

    public List<OccurrenceResponse> listOccurrences(long userId, long scheduleId) {
        requireOwned(scheduleId, userId);
        return repository.findOccurrences(scheduleId, 50).stream().map(this::toOccurrenceResponse).toList();
    }

    /** 触发前完整重鉴权：任一失败抛异常（手动触发）。 */
    public void reauthorize(ScheduleRow schedule) {
        authorizer.requireAuthorized(schedule);
    }

    /** 供调度器调用：重鉴权失败时返回错误码而非抛异常，便于标记 SKIPPED。 */
    public String reauthorizeOrError(ScheduleRow schedule) {
        return authorizer.errorIfUnauthorized(schedule);
    }

    private void validateWorkflow(long userId, long workflowId) {
        workflowRepository.findWorkflowOwned(workflowId, userId)
                .filter(w -> "ACTIVE".equals(w.status()))
                .orElseThrow(() -> new ApiException(ErrorCode.SCHEDULE_FORBIDDEN, HttpStatus.FORBIDDEN,
                        "工作流不存在、已停用或无权访问"));
    }

    private ScheduleRow requireOwned(long id, long userId) {
        return repository.findOwned(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.SCHEDULE_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "计划不存在或无权访问"));
    }

    private String normalizeMisfire(String value) {
        if (value == null || value.isBlank()) {
            return ScheduleStatus.MisfirePolicy.SKIP.name();
        }
        try {
            return ScheduleStatus.MisfirePolicy.valueOf(value.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                    "misfirePolicy 仅支持 SKIP 或 FIRE_ONCE");
        }
    }

    private String normalizeOverlap(String value) {
        if (value == null || value.isBlank()) {
            return ScheduleStatus.OverlapPolicy.SKIP_WHILE_RUNNING.name();
        }
        try {
            return ScheduleStatus.OverlapPolicy.valueOf(value.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                    "overlapPolicy 仅支持 SKIP_WHILE_RUNNING 或 QUEUE_ONE");
        }
    }

    private String normalizeStatus(String value) {
        try {
            return ScheduleStatus.Schedule.valueOf(value.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST,
                    "status 仅支持 ACTIVE 或 PAUSED");
        }
    }

    private ScheduleResponse toResponse(ScheduleRow row) {
        return new ScheduleResponse(
                row.id(), row.workspaceId(), row.workflowId(), row.workflowRevision(), row.connectionId(),
                row.userInput(), row.agentId(), row.agentVersionId(), row.cronExpression(), row.cronNormalized(),
                row.timezone(), row.status(), row.misfirePolicy(), row.overlapPolicy(),
                row.nextFireAt() == null ? null : row.nextFireAt().toString(),
                row.lastFireAt() == null ? null : row.lastFireAt().toString(),
                row.revision(), row.createdAt().toString(), row.updatedAt().toString());
    }

    private OccurrenceResponse toOccurrenceResponse(OccurrenceRow row) {
        return new OccurrenceResponse(
                row.id(), row.scheduleId(),
                row.fireAtUtc() == null ? null : row.fireAtUtc().toString(),
                row.fireAtLocal(), row.workflowRevision(), row.state(), row.attempt(), row.triggerSource(),
                row.workflowRunId(), row.errorCode(), row.errorMessage(),
                row.createdAt().toString(), row.updatedAt().toString());
    }
}
