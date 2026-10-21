package com.queryforge.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.schedule.ScheduleRepository.ScheduleRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 调度轮询器测试：misfire 策略、恢复、鉴权失败跳过、next_fire_at 推进。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("调度轮询器")
class SchedulePollerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @Mock private ScheduleRepository repository;
    @Mock private ScheduleTriggerRunner triggerRunner;
    @Mock private ScheduleAuthorizer authorizer;
    @Mock private ExecutorService executor;

    private SchedulePoller poller;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ScheduleProperties properties = new ScheduleProperties(5000, 60, 10, 15, 1, 4);
        poller = new SchedulePoller(repository, triggerRunner, authorizer, properties, clock, executor);
    }

    private ScheduleRow schedule(String misfire, Instant nextFire) {
        return new ScheduleRow(5L, 1L, null, 10L, 1, 20L, null, "统计订单",
                null, null, "0 0 9 * * 1", "0 0 9 * * 1", "Asia/Shanghai", "ACTIVE",
                misfire, "SKIP_WHILE_RUNNING", nextFire, null, 1,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("misfire SKIP：过旧触发直接跳过并推进，不循环补跑")
    void tick_shouldSkipMisfireAndAdvance() {
        Instant overdue = NOW.minusSeconds(3600); // 超过 15 分钟窗口
        ScheduleRow row = schedule("SKIP", overdue);
        when(repository.findDueSchedules(NOW, 10)).thenReturn(List.of(row));
        when(authorizer.errorIfUnauthorized(row)).thenReturn(null);
        when(repository.findDueOccurrences(NOW, 10)).thenReturn(List.of());

        poller.tick();

        verify(repository).insertSkippedOccurrence(eq(5L), eq(overdue), anyString(), eq(1),
                eq("MISFIRE_SKIPPED"), anyString());
        // 推进到 now 之后：不循环补跑。
        verify(repository).updateNextFireAt(eq(5L), any());
        verify(triggerRunner, never()).claimAndRun(anyLong());
    }

    @Test
    @DisplayName("正常到点：生成 occurrence 并推进 next_fire_at")
    void tick_shouldDispatchDue() {
        Instant due = NOW.minusSeconds(5); // 窗口内
        ScheduleRow row = schedule("SKIP", due);
        when(repository.findDueSchedules(NOW, 10)).thenReturn(List.of(row));
        when(authorizer.errorIfUnauthorized(row)).thenReturn(null);
        when(repository.insertOccurrenceIfAbsent(eq(5L), eq(due), anyString(), eq(1), eq("SCHEDULER")))
                .thenReturn(true);
        when(repository.findDueOccurrences(NOW, 10)).thenReturn(List.of(101L));

        poller.tick();

        verify(repository).updateNextFireAt(eq(5L), any());
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("鉴权失败：不生成 occurrence，仅推进 next_fire_at")
    void tick_shouldSkipOnAuthFailure() {
        Instant due = NOW.minusSeconds(5);
        ScheduleRow row = schedule("SKIP", due);
        when(repository.findDueSchedules(NOW, 10)).thenReturn(List.of(row));
        when(authorizer.errorIfUnauthorized(row)).thenReturn("WORKSPACE_NOT_FOUND");
        when(repository.findDueOccurrences(NOW, 10)).thenReturn(List.of());

        poller.tick();

        verify(repository, never()).insertOccurrenceIfAbsent(anyLong(), any(), anyString(), anyInt(), anyString());
        verify(repository).updateNextFireAt(eq(5L), any());
    }

    @Test
    @DisplayName("重启恢复：回收过期租约并同步非终态")
    void onReady_shouldExpireLeasesAndSync() {
        when(repository.expireLeases(NOW)).thenReturn(2);

        poller.onReady();

        verify(repository).expireLeases(NOW);
        verify(triggerRunner).syncNonTerminal();
    }

    @Test
    @DisplayName("misfire FIRE_ONCE：窗口内补一次后推进到 now 之后")
    void tick_shouldFireOnceOnMisfire() {
        Instant overdue = NOW.minusSeconds(3600);
        ScheduleRow row = schedule("FIRE_ONCE", overdue);
        when(repository.findDueSchedules(NOW, 10)).thenReturn(List.of(row));
        when(authorizer.errorIfUnauthorized(row)).thenReturn(null);
        when(repository.insertOccurrenceIfAbsent(eq(5L), eq(overdue), anyString(), eq(1), eq("SCHEDULER")))
                .thenReturn(true);
        when(repository.findDueOccurrences(NOW, 10)).thenReturn(List.of());

        poller.tick();

        verify(repository).insertOccurrenceIfAbsent(eq(5L), eq(overdue), anyString(), eq(1), eq("SCHEDULER"));
        verify(repository).updateNextFireAt(eq(5L), any());
    }
}
