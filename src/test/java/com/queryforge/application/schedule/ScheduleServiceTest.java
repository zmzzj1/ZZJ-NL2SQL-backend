package com.queryforge.application.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.infrastructure.persistence.ScheduleRepository.OccurrenceRow;
import com.queryforge.infrastructure.persistence.ScheduleRepository.ScheduleRow;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.infrastructure.persistence.WorkflowRepository;
import com.queryforge.infrastructure.persistence.WorkflowRepository.Workflow;
import com.queryforge.application.workspace.WorkspaceAccessPolicy;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.queryforge.application.agent.RuntimeConfigResolver;
import com.queryforge.domain.schedule.CreateScheduleRequest;
import com.queryforge.domain.schedule.OccurrenceResponse;
import com.queryforge.domain.schedule.ScheduleResponse;
import com.queryforge.domain.schedule.UpdateScheduleRequest;
import com.queryforge.infrastructure.persistence.ScheduleRepository;

/**
 * ScheduleService 业务逻辑测试（Mockito，无 Spring 上下文）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("计划服务")
class ScheduleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    @Mock private ScheduleRepository repository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private ConnectionService connectionService;
    @Mock private WorkspaceAccessPolicy workspaceAccessPolicy;
    @Mock private RuntimeConfigResolver configResolver;
    @Mock private ScheduleAuthorizer authorizer;
    @Mock private ScheduleTriggerRunner triggerRunner;

    private ScheduleService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ScheduleService(repository, workflowRepository, connectionService,
                workspaceAccessPolicy, configResolver, authorizer, triggerRunner, clock, null);
    }

    private Workflow workflow(long id) {
        return new Workflow(id, 1L, "销售分析", null, "{}", 1, "ACTIVE",
                LocalDateTime.now(), LocalDateTime.now());
    }

    private ScheduleRow scheduleRow(long id) {
        return new ScheduleRow(id, 1L, null, 10L, 1, 20L, null, "统计订单",
                null, null, "0 0 9 * * 1", "0 0 9 * * 1", "Asia/Shanghai", "ACTIVE",
                "SKIP", "SKIP_WHILE_RUNNING", NOW.plusSeconds(60), null, 1,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("创建计划时校验 workflow 归属、连接归属并计算 nextFireAt")
    void create_shouldValidateAndPersist() {
        when(workflowRepository.findWorkflowOwned(10L, 1L)).thenReturn(Optional.of(workflow(10L)));
        when(repository.insertSchedule(any())).thenAnswer(inv -> {
            ScheduleRow row = inv.getArgument(0);
            return 5L;
        });
        when(repository.findOwned(5L, 1L)).thenReturn(Optional.of(scheduleRow(5L)));

        ScheduleResponse response = service.create(1L, new CreateScheduleRequest(
                null, 10L, 20L, "统计订单", null, null, "0 9 * * 1", "Asia/Shanghai", null, null));

        verify(connectionService).summary(1L, 20L);
        assertThat(response.workflowRevision()).isEqualTo(1);
        assertThat(response.cronNormalized()).isEqualTo("0 0 9 * * 1");
    }

    @Test
    @DisplayName("创建计划时 workflow 非本人或停用被拒绝")
    void create_shouldRejectUnauthorizedWorkflow() {
        when(workflowRepository.findWorkflowOwned(10L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(1L, new CreateScheduleRequest(
                null, 10L, 20L, null, null, null, "0 9 * * 1", "Asia/Shanghai", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_FORBIDDEN);
    }

    @Test
    @DisplayName("创建计划时非法 cron 被拒绝")
    void create_shouldRejectInvalidCron() {
        when(workflowRepository.findWorkflowOwned(10L, 1L)).thenReturn(Optional.of(workflow(10L)));

        assertThatThrownBy(() -> service.create(1L, new CreateScheduleRequest(
                null, 10L, 20L, null, null, null, "bad cron", "Asia/Shanghai", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_INVALID_CRON);
    }

    @Test
    @DisplayName("创建计划时 workspace 非成员被拒绝")
    void create_shouldRejectNonMember() {
        when(workflowRepository.findWorkflowOwned(10L, 1L)).thenReturn(Optional.of(workflow(10L)));
        when(connectionService.summary(1L, 20L)).thenReturn(null);
        when(workspaceAccessPolicy.requireMember(99L, 1L))
                .thenThrow(new ApiException(ErrorCode.WORKSPACE_NOT_FOUND, org.springframework.http.HttpStatus.NOT_FOUND, "x"));

        assertThatThrownBy(() -> service.create(1L, new CreateScheduleRequest(
                99L, 10L, 20L, null, null, null, "0 9 * * 1", "Asia/Shanghai", null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND);
    }

    @Test
    @DisplayName("更新计划 revision 冲突被拒绝")
    void update_shouldRejectRevisionConflict() {
        when(repository.findOwned(5L, 1L)).thenReturn(Optional.of(scheduleRow(5L)));
        when(repository.updateSchedule(eq(5L), eq(1L), eq(1), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.update(1L, 5L, 1, new UpdateScheduleRequest(null, null, null, null, "PAUSED", null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_CONFLICT);
    }

    @Test
    @DisplayName("手动触发暂停计划被拒绝")
    void triggerNow_shouldRejectPaused() {
        ScheduleRow paused = new ScheduleRow(5L, 1L, null, 10L, 1, 20L, null, "统计订单",
                null, null, "0 0 9 * * 1", "0 0 9 * * 1", "Asia/Shanghai", "PAUSED",
                "SKIP", "SKIP_WHILE_RUNNING", NOW.plusSeconds(60), null, 1,
                LocalDateTime.now(), LocalDateTime.now());
        when(repository.findOwned(5L, 1L)).thenReturn(Optional.of(paused));

        assertThatThrownBy(() -> service.triggerNow(1L, 5L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_DISABLED);
    }

    @Test
    @DisplayName("手动触发幂等：重复触发返回同一 occurrence")
    void triggerNow_shouldBeIdempotent() {
        ScheduleRow active = scheduleRow(5L);
        when(repository.findOwned(5L, 1L)).thenReturn(Optional.of(active));

        OccurrenceRow occurrence = new OccurrenceRow(1L, 5L, NOW, "2026-10-20 08:00:00", 1,
                "SCHEDULED", 1, "MANUAL", null, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now());
        when(repository.insertOccurrenceIfAbsent(eq(5L), any(), anyString(), eq(1), eq("MANUAL")))
                .thenReturn(false);
        when(repository.findOccurrences(5L, 1)).thenReturn(List.of(occurrence));

        OccurrenceResponse response = service.triggerNow(1L, 5L);

        assertThat(response.id()).isEqualTo(1L);
        verify(triggerRunner, never()).runOccurrence(any());
    }

    @Test
    @DisplayName("删除他人计划返回 404")
    void delete_shouldReturnNotFoundForOthers() {
        when(repository.deleteOwned(5L, 2L)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(2L, 5L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);
    }
}
