package com.queryforge.schedule;

import com.queryforge.schedule.ScheduleRepository.OccurrenceRow;
import com.queryforge.schedule.ScheduleRepository.ScheduleRow;
import com.queryforge.schedule.ScheduleStatus.OverlapPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时调度轮询器：推进到点计划的 next_fire_at 并生成 occurrence，再由执行器原子领取执行。
 *
 * 设计要点：
 *  - 轮询线程（@Scheduled 单线程）只做「推进 + 生成 occurrence + 提交执行」，实际执行在独立有界 executor，
 *    避免 SQL/LLM 调用阻塞轮询。
 *  - misfire：SKIP 时超过 catchUpWindowMinutes 的到期直接跳到 now 之后下一次，不循环补跑；
 *    FIRE_ONCE 时窗口内只补一次。
 *  - 重启恢复：@EventListener(ApplicationReadyEvent) 回收过期租约、同步非终态 occurrence 状态。
 *  - 幂等：occurrence 生成走唯一约束 (schedule_id, fire_at_utc, workflow_revision, attempt)；
 *    领取走条件更新 state='SCHEDULED' -> 'CLAIMED'。
 */
@Component
public class SchedulePoller {

    private static final Logger log = LoggerFactory.getLogger(SchedulePoller.class);

    private final ScheduleRepository repository;
    private final ScheduleTriggerRunner triggerRunner;
    private final ScheduleAuthorizer authorizer;
    private final ScheduleProperties properties;
    private final Clock clock;
    private final ExecutorService executor;

    public SchedulePoller(ScheduleRepository repository, ScheduleTriggerRunner triggerRunner,
                          ScheduleAuthorizer authorizer, ScheduleProperties properties, Clock clock,
                          ExecutorService scheduleExecutor) {
        this.repository = repository;
        this.triggerRunner = triggerRunner;
        this.authorizer = authorizer;
        this.properties = properties;
        this.clock = clock;
        this.executor = scheduleExecutor;
    }

    @Scheduled(fixedDelayString = "${app.schedule.poll-interval-ms:5000}")
    public void tick() {
        try {
            expireLeases();
            dispatchDue();
        } catch (Exception ex) {
            log.warn("schedule_tick_error message={}", ex.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("schedule_poller_ready");
        expireLeases();
        triggerRunner.syncNonTerminal();
    }

    private void expireLeases() {
        int expired = repository.expireLeases(clock.instant());
        if (expired > 0) {
            log.info("schedule_lease_expired count={}", expired);
        }
    }

    private void dispatchDue() {
        Instant now = clock.instant();
        List<ScheduleRow> due = repository.findDueSchedules(now, properties.maxClaimBatch());
        for (ScheduleRow schedule : due) {
            try {
                advance(schedule, now);
            } catch (Exception ex) {
                log.warn("schedule_advance_failed scheduleId={} message={}", schedule.id(), ex.getMessage());
            }
        }
        // 领取并提交执行到独立 executor。
        List<Long> occurrenceIds = repository.findDueOccurrences(now, properties.maxClaimBatch());
        for (Long occurrenceId : occurrenceIds) {
            executor.execute(() -> triggerRunner.claimAndRun(occurrenceId));
        }
    }

    /** 推进单个到点计划：生成一次 occurrence 并重算 next_fire_at。 */
    private void advance(ScheduleRow schedule, Instant now) {
        Instant fireAt = schedule.nextFireAt();

        // 鉴权：撤权后不再生成新触发，直接推进（不产生 occurrence）。
        String authError = authorizer.errorIfUnauthorized(schedule);
        if (authError != null) {
            scheduleToNext(schedule, now);
            return;
        }

        // misfire 判定：长期停机后到点时间过旧。
        Duration overdue = Duration.between(fireAt, now);
        boolean missed = overdue.compareTo(Duration.ofMinutes(properties.catchUpWindowMinutes())) > 0;

        if (missed && ScheduleStatus.MisfirePolicy.SKIP.name().equals(schedule.misfirePolicy())) {
            repository.insertSkippedOccurrence(schedule.id(), fireAt, localString(schedule, fireAt),
                    schedule.workflowRevision(), "MISFIRE_SKIPPED", "错过触发窗口，按 SKIP 策略跳过");
            scheduleToNext(schedule, now);
            return;
        }

        boolean created = repository.insertOccurrenceIfAbsent(
                schedule.id(), fireAt, localString(schedule, fireAt),
                schedule.workflowRevision(), ScheduleStatus.TriggerSource.SCHEDULER.name());

        if (missed && ScheduleStatus.MisfirePolicy.FIRE_ONCE.name().equals(schedule.misfirePolicy())) {
            // FIRE_ONCE：只补这一次，推进到 now 之后。
            scheduleToNext(schedule, now);
            return;
        }

        if (created) {
            scheduleToNext(schedule, fireAt);
        }
    }

    /** 把 next_fire_at 推进到严格晚于 from 的下一次 cron 时刻；misfire 时直接跳到 now 之后，不循环补跑。 */
    private void scheduleToNext(ScheduleRow schedule, Instant from) {
        CronSchedule cron = CronSchedule.of(schedule.cronExpression(), schedule.timezone());
        Instant next = cron.nextAfter(from);
        repository.updateNextFireAt(schedule.id(), next);
    }

    private String localString(ScheduleRow schedule, Instant fireAt) {
        return fireAt.atZone(ZoneId.of(schedule.timezone()))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
