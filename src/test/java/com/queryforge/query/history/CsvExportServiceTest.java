package com.queryforge.query.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.datasource.ConnectionService;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.query.execution.QueryResultResponse;
import com.queryforge.query.execution.ReadOnlySqlGuard;
import com.queryforge.query.execution.TargetQueryRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CSV 导出服务")
class CsvExportServiceTest {

    private static final long USER_ID = 1L;
    private static final long HISTORY_ID = 5L;
    private static final long CONNECTION_ID = 10L;
    private static final String SQL = "SELECT * FROM shop_orders";
    private static final ConnectionTarget TARGET = new ConnectionTarget(
            CONNECTION_ID, "A 商店库", "127.0.0.1", 3306, "sql_demo_a", "demo_a_reader", "secret");

    @Mock
    private QueryHistoryService historyService;
    @Mock
    private ConnectionService connectionService;
    @Mock
    private TargetQueryRepository repository;
    @Mock
    private ReadOnlySqlGuard guard;

    private CsvExportService service;

    @BeforeEach
    void setUp() {
        service = new CsvExportService(historyService, connectionService, repository, guard);
    }

    private QueryHistoryResponse successHistory() {
        return new QueryHistoryResponse(
                HISTORY_ID, null, null, 7L, CONNECTION_ID, "A 商店库", SQL, QueryStatus.SUCCESS,
                java.time.LocalDateTime.now(), 12, 1, false, null, null);
    }

    @Test
    @DisplayName("导出会重新鉴权、重新过 Guard、重新执行取真实行")
    void export_shouldReauthorizeAndReexecute() throws Exception {
        when(historyService.detail(USER_ID, HISTORY_ID)).thenReturn(successHistory());
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);
        when(guard.validate(SQL, "sql_demo_a")).thenReturn(SQL);
        when(repository.query(eq(TARGET), eq(SQL), anyInt(), anyInt()))
                .thenReturn(new QueryResultResponse(
                        List.of("id", "name"), List.of(Map.of("id", 1, "name", "=1+1")), 1, false));

        CsvExportService.CsvExport export = service.export(USER_ID, HISTORY_ID);

        assertThat(export.filename()).isEqualTo("query-5.csv");
        String csv = new String(export.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("id,name");
        assertThat(csv).contains("'=1+1");
        verify(repository).query(eq(TARGET), eq(SQL), anyInt(), anyInt());
    }

    @Test
    @DisplayName("非成功状态的记录拒绝导出")
    void export_shouldRejectNonSuccess() throws Exception {
        when(historyService.detail(USER_ID, HISTORY_ID)).thenReturn(new QueryHistoryResponse(
                HISTORY_ID, null, null, 7L, CONNECTION_ID, "A 商店库", SQL, QueryStatus.FAILED,
                java.time.LocalDateTime.now(), 12, null, null, "SQL_EXECUTION_ERROR", "boom"));

        assertThatThrownBy(() -> service.export(USER_ID, HISTORY_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXPORT_TOO_LARGE));
        verify(repository, never()).query(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("SQL 被 Guard 拒绝时不执行导出查询")
    void export_shouldNotExecute_whenGuardRejects() throws Exception {
        when(historyService.detail(USER_ID, HISTORY_ID)).thenReturn(successHistory());
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);
        when(guard.validate(SQL, "sql_demo_a"))
                .thenThrow(new ApiException(ErrorCode.SQL_NOT_ALLOWED, org.springframework.http.HttpStatus.BAD_REQUEST, "只读"));

        assertThatThrownBy(() -> service.export(USER_ID, HISTORY_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_NOT_ALLOWED));
        verify(repository, never()).query(any(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("公式注入前缀被加单引号转义")
    void escape_shouldNeutralizeFormulaPrefixes() {
        assertThat(CsvExportService.escape("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(CsvExportService.escape("+cmd")).isEqualTo("'+cmd");
        assertThat(CsvExportService.escape("-2+3")).isEqualTo("'-2+3");
        assertThat(CsvExportService.escape("@import")).isEqualTo("'@import");
        assertThat(CsvExportService.escape("\t tab")).isEqualTo("'\t tab");
        assertThat(CsvExportService.escape("normal")).isEqualTo("normal");
        assertThat(CsvExportService.escape("")).isEqualTo("");
    }

    @Test
    @DisplayName("含逗号/引号/换行时按 RFC 4180 加引号")
    void escape_shouldQuoteSpecialChars() {
        assertThat(CsvExportService.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvExportService.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvExportService.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }
}
