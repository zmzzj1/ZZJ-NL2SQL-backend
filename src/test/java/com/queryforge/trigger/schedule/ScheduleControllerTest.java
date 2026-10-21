package com.queryforge.trigger.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.queryforge.domain.identity.AppUserPrincipal;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.queryforge.application.schedule.ScheduleService;
import com.queryforge.domain.schedule.OccurrenceResponse;
import com.queryforge.domain.schedule.ScheduleResponse;

@WebMvcTest(ScheduleController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("定时计划接口")
class ScheduleControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ScheduleService service;

    @BeforeEach
    void authenticate() {
        AppUserPrincipal principal = new AppUserPrincipal(1L, "alice@example.com", "Alice", "hash");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private ScheduleResponse schedule() {
        return new ScheduleResponse(5L, null, 10L, 1, 20L, "统计订单", null, null,
                "0 0 9 * * 1", "0 0 9 * * 1", "Asia/Shanghai", "ACTIVE", "SKIP", "SKIP_WHILE_RUNNING",
                "2026-10-26T01:00:00Z", null, 1, "2026-10-20T00:00:00", "2026-10-20T00:00:00");
    }

    @Test
    @DisplayName("创建计划返回 201 与 nextFireAt")
    void create_shouldReturnCreated() throws Exception {
        when(service.create(eq(1L), any())).thenReturn(schedule());

        mockMvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":10,\"connectionId\":20,\"cron\":\"0 9 * * 1\",\"timezone\":\"Asia/Shanghai\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.nextFireAt").value("2026-10-26T01:00:00Z"));
    }

    @Test
    @DisplayName("创建计划非法 cron 返回 422")
    void create_shouldReturnUnprocessableOnInvalidCron() throws Exception {
        when(service.create(eq(1L), any()))
                .thenThrow(new ApiException(ErrorCode.SCHEDULE_INVALID_CRON, HttpStatus.UNPROCESSABLE_ENTITY, "bad"));

        mockMvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":10,\"connectionId\":20,\"cron\":\"bad\",\"timezone\":\"Asia/Shanghai\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEDULE_INVALID_CRON"));
    }

    @Test
    @DisplayName("列出计划只返回本人（userId 来自 principal）")
    void list_shouldPassPrincipalId() throws Exception {
        when(service.list(1L)).thenReturn(List.of(schedule()));

        mockMvc.perform(get("/api/v1/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5));
    }

    @Test
    @DisplayName("更新计划 revision 冲突返回 409")
    void update_shouldReturnConflict() throws Exception {
        when(service.update(eq(1L), eq(5L), eq(1), any()))
                .thenThrow(new ApiException(ErrorCode.SCHEDULE_CONFLICT, HttpStatus.CONFLICT, "conflict"));

        mockMvc.perform(patch("/api/v1/schedules/5").param("revision", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICT"));
    }

    @Test
    @DisplayName("删除他人计划返回 404")
    void delete_shouldReturnNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(ErrorCode.SCHEDULE_NOT_FOUND, HttpStatus.NOT_FOUND, "not found"))
                .when(service).delete(1L, 5L);

        mockMvc.perform(delete("/api/v1/schedules/5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }

    @Test
    @DisplayName("手动触发返回 occurrence")
    void trigger_shouldReturnOccurrence() throws Exception {
        OccurrenceResponse occurrence = new OccurrenceResponse(1L, 5L, "2026-10-20T00:00:00Z",
                "2026-10-20 08:00:00", 1, "WAITING_APPROVAL", 1, "MANUAL", 7L, null, null, "", "");
        when(service.triggerNow(1L, 5L)).thenReturn(occurrence);

        mockMvc.perform(post("/api/v1/schedules/5/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowRunId").value(7));
    }

    @Test
    @DisplayName("触发历史列表返回 state 与 runId")
    void occurrences_shouldReturnList() throws Exception {
        OccurrenceResponse occurrence = new OccurrenceResponse(1L, 5L, "2026-10-20T00:00:00Z",
                null, 1, "SUCCEEDED", 1, "SCHEDULER", 7L, null, null, "", "");
        when(service.listOccurrences(1L, 5L)).thenReturn(List.of(occurrence));

        mockMvc.perform(get("/api/v1/schedules/5/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("SUCCEEDED"));
    }
}
