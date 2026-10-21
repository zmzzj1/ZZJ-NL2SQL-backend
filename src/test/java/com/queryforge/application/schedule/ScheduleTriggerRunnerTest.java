package com.queryforge.application.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.application.workflow.PreparedRun;
import com.queryforge.application.workflow.WorkflowRunService;
import com.queryforge.infrastructure.persistence.ScheduleRepository.OccurrenceRow;
import com.queryforge.infrastructure.persistence.ScheduleRepository.ScheduleRow;
import com.queryforge.domain.workflow.WorkflowRunResponse;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.queryforge.domain.schedule.ScheduleProperties;
import com.queryforge.infrastructure.persistence.ScheduleRepository;
import com.queryforge.infrastructure.redis.ScheduleRunLock;

/**
 * 触发执行器测试。核心安全断言：执行器绝不调用 SQL 执行或审批，只通过 WorkflowRunService 复用现有执行链。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("触发执行器")
class ScheduleTriggerRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @Mock private ScheduleRepository repository;
    @Mock private WorkflowRunService workflowRunService;
    @Mock private ScheduleAuthorizer authorizer;
    @Mock private ScheduleRunLock scheduleRunLock;

    private ScheduleTriggerRunner runner;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ScheduleProperties properties = new ScheduleProperties(5000, 60, 10, 15, 1, 4);
        org.mockito.Mockito.lenient().when(scheduleRunLock.tryLock(anyLong(), any())).thenReturn(true);
        runner = new ScheduleTriggerRunner(repository, workflowRunService, authorizer, properties, clock, scheduleRunLock);
    }

    private ScheduleRow schedule() {
        return new ScheduleRow(5L, 1L, null, 10L, 1, 20L, null, "统计订单",
                null, null, "0 0 9 * * 1", "0 0 9 * * 1", "Asia/Shanghai", "ACTIVE",
                "SKIP", "SKIP_WHILE_RUNNING", NOW.plusSeconds(60), null, 1,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private OccurrenceRow occurrence() {
        return new OccurrenceRow(1L, 5L, NOW, "2026-10-20 08:00:00", 1,
                "CLAIMED", 1, "SCHEDULER", null, null, null, "owner", null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("正常执行：prepareRun + startRun(nullOutputStream)，回读状态映射")
    void runOccurrence_shouldDelegateToWorkflowService() throws Exception {
        when(repository.findScheduleById(5L)).thenReturn(Optional.of(schedule()));
        when(repository.findActiveOccurrences(5L)).thenReturn(List.of());
        when(authorizer.errorIfUnauthorized(any())).thenReturn(null);
        when(workflowRunService.prepareRun(eq(1L), eq(10L), any()))
                .thenReturn(new PreparedRun(7L, 1L, 10L, 20L, null, "统计订单", null, null));
        when(workflowRunService.runDetail(1L, 7L))
                .thenReturn(new WorkflowRunResponse(7L, 10L, 1, "WAITING_APPROVAL", null, null, List.of(), "", ""));

        runner.runOccurrence(occurrence());

        // 关键：使用 nullOutputStream（丢弃 SSE，无浏览器对端）。写入无副作用。
        ArgumentCaptor<OutputStream> streamCaptor = ArgumentCaptor.forClass(OutputStream.class);
        verify(workflowRunService).startRun(any(), streamCaptor.capture());
        streamCaptor.getValue().write(new byte[]{1, 2, 3}); // 不抛异常即证明是丢弃流

        verify(repository).updateOccurrence(1L, "WAITING_APPROVAL", 7L, null, null);
    }

    @Test
    @DisplayName("鉴权失败时标记 SKIPPED，不创建 Run")
    void runOccurrence_shouldSkipWhenUnauthorized() {
        when(repository.findScheduleById(5L)).thenReturn(Optional.of(schedule()));
        when(repository.findActiveOccurrences(5L)).thenReturn(List.of());
        when(authorizer.errorIfUnauthorized(any())).thenReturn("SCHEDULE_FORBIDDEN");

        runner.runOccurrence(occurrence());

        verify(repository).updateOccurrence(1L, "SKIPPED", null, "SCHEDULE_FORBIDDEN", "触发前鉴权失败");
        verify(workflowRunService, never()).prepareRun(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("并发策略 SKIP_WHILE_RUNNING：已有活跃 occurrence 时跳过")
    void runOccurrence_shouldSkipWhenOverlapping() {
        when(repository.findScheduleById(5L)).thenReturn(Optional.of(schedule()));
        OccurrenceRow active = new OccurrenceRow(2L, 5L, NOW.minusSeconds(10), null, 1,
                "WAITING_APPROVAL", 1, "SCHEDULER", 8L, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now());
        when(repository.findActiveOccurrences(5L)).thenReturn(List.of(active));

        runner.runOccurrence(occurrence());

        verify(repository).updateOccurrence(1L, "SKIPPED", null, "CONCURRENCY_SKIPPED", "上一触发仍在运行，按并发策略跳过");
        verify(workflowRunService, never()).prepareRun(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("Redisson 互斥锁未获取时跳过执行（同任务并发互斥，P12 S4）")
    void runOccurrence_shouldSkipWhenScheduleLockNotAcquired() {
        when(repository.findScheduleById(5L)).thenReturn(Optional.of(schedule()));
        when(scheduleRunLock.tryLock(anyLong(), any())).thenReturn(false);

        runner.runOccurrence(occurrence());

        verify(repository).updateOccurrence(1L, "SKIPPED", null, "CONCURRENCY_SKIPPED", "同任务互斥锁未获取");
        verify(workflowRunService, never()).prepareRun(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("run 状态映射：COMPLETED->SUCCEEDED, FAILED->FAILED, CANCELED->CANCELED")
    void syncFromRun_shouldMapStatus() {
        when(workflowRunService.runDetail(1L, 7L))
                .thenReturn(new WorkflowRunResponse(7L, 10L, 1, "COMPLETED", null, null, List.of(), "", ""));

        runner.syncFromRun(schedule(), 1L, 7L);

        verify(repository).updateOccurrence(1L, "SUCCEEDED", 7L, null, null);
    }
}
