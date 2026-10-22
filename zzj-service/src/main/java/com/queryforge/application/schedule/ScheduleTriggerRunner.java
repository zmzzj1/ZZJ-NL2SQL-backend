package com.queryforge.application.schedule;

import com.queryforge.application.workflow.PreparedRun;
import com.queryforge.application.workflow.WorkflowRunService;
import com.queryforge.infrastructure.persistence.ScheduleRepository.OccurrenceRow;
import com.queryforge.infrastructure.persistence.ScheduleRepository.ScheduleRow;
import com.queryforge.domain.workflow.StartWorkflowRunRequest;
import com.queryforge.domain.workflow.WorkflowRunResponse;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.queryforge.domain.schedule.ScheduleProperties;
import com.queryforge.domain.schedule.ScheduleStatus;
import com.queryforge.infrastructure.persistence.ScheduleRepository;
import com.queryforge.infrastructure.redis.ScheduleRunLock;

/**
 * 触发执行器：把一次 occurrence 转换为现有 Workflow Run 并执行到待审批/终态。
 *
 * 关键安全保证：
 *  - 唯一执行通道是 {@link WorkflowRunService#prepareRun} + {@link WorkflowRunService#startRun}，
 *    使用 {@code OutputStream.nullOutputStream()} 丢弃 SSE（无浏览器对端）。
 *  - 绝不调用 {@code SqlExecutionService.execute} 或任何审批方法；SQL 节点由 WorkflowRunService
 *    内部生成 SQL 后停在 WAITING_APPROVAL，等待用户通过既有审批接口批准。
 *  - 并发策略：SKIP_WHILE_RUNNING 时若已有活跃 occurrence 则 SKIPPED；QUEUE_ONE 时最多保留一个待执行。
 *  - 运行结果回读 workflow run 状态，映射并落库；映射只在非终态时进行，终态不可被覆写。
 */
@Service
public class ScheduleTriggerRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTriggerRunner.class);

    private final ScheduleRepository repository;
    private final WorkflowRunService workflowRunService;
    private final ScheduleAuthorizer authorizer;
    private final ScheduleProperties properties;
    private final Clock clock;
    private final ScheduleRunLock scheduleRunLock;

    public ScheduleTriggerRunner(ScheduleRepository repository, WorkflowRunService workflowRunService,
                                 ScheduleAuthorizer authorizer, ScheduleProperties properties, Clock clock,
                                 ScheduleRunLock scheduleRunLock) {
        this.repository = repository;
        this.workflowRunService = workflowRunService;
        this.authorizer = authorizer;
        this.properties = properties;
        this.clock = clock;
        this.scheduleRunLock = scheduleRunLock;
    }

    /** 领取并执行一个 occurrence（调度器调用）。返回是否成功领取。 */
    public boolean claimAndRun(long occurrenceId) {
        String leaseOwner = leaseOwner();
        Instant leaseExpires = clock.instant().plusSeconds(properties.leaseSeconds());
        if (!repository.claimOccurrence(occurrenceId, leaseOwner, leaseExpires)) {
            return false; // 被其他 worker 抢到
        }
        OccurrenceRow occurrence = repository.findOccurrenceById(occurrenceId)
                .orElseThrow(() -> new IllegalStateException("occurrence " + occurrenceId + " 不存在"));
        runOccurrence(occurrence);
        return true;
    }

    /** 执行一个已领取的 occurrence（同步执行到待审批/终态）。 */
    public void runOccurrence(OccurrenceRow occurrence) {
        ScheduleRow schedule = repository.findScheduleById(occurrence.scheduleId())
                .orElseThrow(() -> new IllegalStateException("schedule " + occurrence.scheduleId() + " 不存在"));

        // 跨实例互斥（Redisson RLock）：同 schedule 已有实例在跑则跳过，减少 DB 领取争用。
        // 最终幂等仍由 claimOccurrence 的 DB 唯一约束保证。
        boolean locked = scheduleRunLock.tryLock(schedule.id(), Duration.ofSeconds(properties.leaseSeconds()));
        if (!locked) {
            repository.updateOccurrence(occurrence.id(), ScheduleStatus.Occurrence.SKIPPED.name(),
                    null, ErrorCodeName.CONCURRENCY_SKIPPED, "同任务互斥锁未获取");
            return;
        }
        try {
            // 并发策略校验：已有活跃 occurrence 时按策略处理。
            if (shouldSkipForConcurrency(schedule)) {
                repository.updateOccurrence(occurrence.id(), ScheduleStatus.Occurrence.SKIPPED.name(),
                        null, ErrorCodeName.CONCURRENCY_SKIPPED, "上一触发仍在运行，按并发策略跳过");
                return;
            }

            // 重鉴权：撤权/资源失效后不再执行。
            String authError = authorizer.errorIfUnauthorized(schedule);
            if (authError != null) {
                repository.updateOccurrence(occurrence.id(), ScheduleStatus.Occurrence.SKIPPED.name(),
                        null, authError, "触发前鉴权失败");
                return;
            }

            try {
                PreparedRun prepared = workflowRunService.prepareRun(
                        schedule.ownerUserId(), schedule.workflowId(),
                        new StartWorkflowRunRequest(
                                schedule.connectionId(), schedule.conversationId(), schedule.userInput(),
                                schedule.agentId(), schedule.agentVersionId()));

                repository.updateOccurrence(occurrence.id(), ScheduleStatus.Occurrence.RUNNING.name(),
                        prepared.runId(), null, null);

                workflowRunService.startRun(prepared, OutputStream.nullOutputStream());

                syncFromRun(schedule, occurrence.id(), prepared.runId());
            } catch (Exception ex) {
                log.warn("schedule_run_failed scheduleId={} occurrenceId={} message={}",
                        schedule.id(), occurrence.id(), ex.getMessage());
                repository.updateOccurrence(occurrence.id(), ScheduleStatus.Occurrence.FAILED.name(),
                        null, "WORKFLOW_FAILED", safe(ex.getMessage()));
            }
        } finally {
            scheduleRunLock.unlock(schedule.id());
        }
    }

    /** 根据 workflow run 状态同步 occurrence 状态（仅非终态推进，终态不覆写）。 */
    public void syncFromRun(ScheduleRow schedule, long occurrenceId, long runId) {
        WorkflowRunResponse run;
        try {
            run = workflowRunService.runDetail(schedule.ownerUserId(), runId);
        } catch (Exception ex) {
            repository.updateOccurrence(occurrenceId, ScheduleStatus.Occurrence.FAILED.name(),
                    runId, "WORKFLOW_RUN_READ_FAILED", safe(ex.getMessage()));
            return;
        }
        String mapped = mapRunStatus(run.status());
        repository.updateOccurrence(occurrenceId, mapped, runId, run.errorCode(), run.errorMessage());
    }

    /** 对所有非终态 occurrence 惰性同步（重启恢复 / 定时扫描）。 */
    public void syncNonTerminal() {
        List<OccurrenceRow> pending = repository.findNonTerminalOccurrences();
        for (OccurrenceRow occurrence : pending) {
            if (occurrence.workflowRunId() == null) {
                continue;
            }
            ScheduleRow schedule = repository.findScheduleById(occurrence.scheduleId()).orElse(null);
            if (schedule == null) {
                continue;
            }
            try {
                WorkflowRunResponse run = workflowRunService.runDetail(schedule.ownerUserId(), occurrence.workflowRunId());
                String mapped = mapRunStatus(run.status());
                repository.updateOccurrence(occurrence.id(), mapped, occurrence.workflowRunId(),
                        run.errorCode(), run.errorMessage());
            } catch (Exception ex) {
                log.warn("schedule_sync_failed occurrenceId={} message={}", occurrence.id(), ex.getMessage());
            }
        }
    }

    private boolean shouldSkipForConcurrency(ScheduleRow schedule) {
        List<OccurrenceRow> active = repository.findActiveOccurrences(schedule.id());
        if (active.isEmpty()) {
            return false;
        }
        if (ScheduleStatus.OverlapPolicy.QUEUE_ONE.name().equals(schedule.overlapPolicy())) {
            // QUEUE_ONE：最多允许 1 个活跃（正在排队的）存在；超过则跳过。
            return active.size() >= 1;
        }
        // SKIP_WHILE_RUNNING（默认）：存在活跃即跳过。
        return true;
    }

    private String mapRunStatus(String status) {
        return switch (status) {
            case "WAITING_APPROVAL" -> ScheduleStatus.Occurrence.WAITING_APPROVAL.name();
            case "COMPLETED" -> ScheduleStatus.Occurrence.SUCCEEDED.name();
            case "FAILED" -> ScheduleStatus.Occurrence.FAILED.name();
            case "CANCELED" -> ScheduleStatus.Occurrence.CANCELED.name();
            default -> ScheduleStatus.Occurrence.RUNNING.name();
        };
    }

    private String leaseOwner() {
        return java.net.InetAddress.getLoopbackAddress().getHostName() + ":" +
                java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "调度执行失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    /** 内部错误码常量，避免直接依赖 ErrorCode（保持状态机独立）。 */
    private static final class ErrorCodeName {
        static final String CONCURRENCY_SKIPPED = "CONCURRENCY_SKIPPED";
        private ErrorCodeName() {
        }
    }
}
