package com.queryforge.query.history;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.queryforge.shared.pagination.PageResponse;
import com.queryforge.query.history.QueryHistoryResponse;
import com.queryforge.query.history.QueryStatus;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.query.history.QueryHistoryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QueryHistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("查询历史接口")
class QueryHistoryControllerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 6, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueryHistoryService service;

    @MockitoBean
    private CsvExportService exportService;

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

    @Test
    @DisplayName("列表支持 limit、cursor 与 connectionId 筛选")
    void list_shouldReturnPage() throws Exception {
        when(service.list(1L, 20, "cursor-1", 10L))
                .thenReturn(new PageResponse<>(List.of(record(3L)), null));

        mockMvc.perform(get("/api/v1/query-history")
                        .param("limit", "20")
                        .param("cursor", "cursor-1")
                        .param("connectionId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(3))
                .andExpect(jsonPath("$.items[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.items[0].rowCount").value(1))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("删除返回 204")
    void delete_shouldReturnNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/query-history/3"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("他人的记录返回 404 与 HISTORY_NOT_FOUND")
    void detail_shouldReturnNotFound() throws Exception {
        doThrow(new ApiException(
                ErrorCode.HISTORY_NOT_FOUND, HttpStatus.NOT_FOUND, "查询记录不存在或无权访问"))
                .when(service).detail(1L, 3L);

        mockMvc.perform(get("/api/v1/query-history/3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HISTORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("导出成功记录返回 text/csv 附件")
    void export_shouldReturnCsvAttachment() throws Exception {
        byte[] body = "id,name\r\n1,'=1+1\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(exportService.export(1L, 3L))
                .thenReturn(new CsvExportService.CsvExport("query-3.csv", body));

        mockMvc.perform(get("/api/v1/query-history/3/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("query-3.csv")));
    }

    @Test
    @DisplayName("导出他人记录返回 404")
    void export_shouldRejectForeignRecord() throws Exception {
        doThrow(new ApiException(
                ErrorCode.HISTORY_NOT_FOUND, HttpStatus.NOT_FOUND, "查询记录不存在或无权访问"))
                .when(exportService).export(1L, 3L);

        mockMvc.perform(get("/api/v1/query-history/3/export"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HISTORY_NOT_FOUND"));
    }

    private static QueryHistoryResponse record(long id) {
        return new QueryHistoryResponse(
                id, null, null, 7L, 10L, "A 商店库", "SELECT 1", QueryStatus.SUCCESS,
                NOW, 12, 1, false, null, null);
    }
}
