package com.queryforge.query.history;
import com.queryforge.shared.QueryContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queryforge.shared.pagination.PageResponse;
import com.queryforge.query.history.QueryHistoryResponse;
import com.queryforge.query.execution.QueryResultResponse;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.query.history.QueryHistoryRecord;
import com.queryforge.query.history.QueryStatus;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.query.history.QueryHistoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("查询历史服务")
class QueryHistoryServiceTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final String SQL = "SELECT COUNT(*) AS `订单数` FROM shop_orders";
    private static final ConnectionTarget TARGET = new ConnectionTarget(
            CONNECTION_ID, "A 商店库", "127.0.0.1", 3306, "sql_demo_a", "demo_a_reader", "secret");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 6, 0);

    @Mock
    private QueryHistoryRepository repository;

    private QueryHistoryService service;

    @BeforeEach
    void setUp() {
        service = new QueryHistoryService(repository);
    }

    @Test
    @DisplayName("执行成功写入行数与截断标记，不保存结果行")
    void recordSuccess_shouldPersistMetadataOnly() {
        QueryResultResponse result = new QueryResultResponse(
                List.of("订单数"), List.of(Map.of("订单数", 3)), 1, false);

        service.recordSuccess(new QueryContext(USER_ID, 20L, null, null), TARGET, SQL, 42, result);

        QueryHistoryRecord record = captureInsert();
        assertThat(record.status()).isEqualTo(QueryStatus.SUCCESS);
        assertThat(record.connectionName()).isEqualTo("A 商店库");
        assertThat(record.rowCount()).isEqualTo(1);
        assertThat(record.truncated()).isFalse();
        assertThat(record.errorCode()).isNull();
        assertThat(record.durationMs()).isEqualTo(42);
    }

    @Test
    @DisplayName("执行失败只保留错误分类与截断后的安全摘要")
    void recordFailure_shouldTruncateErrorMessage() {
        service.recordFailure(new QueryContext(USER_ID, null, null, null), TARGET, SQL, 5,
                ErrorCode.SQL_EXECUTION_ERROR, "x".repeat(400));

        QueryHistoryRecord record = captureInsert();
        assertThat(record.status()).isEqualTo(QueryStatus.FAILED);
        assertThat(record.errorCode()).isEqualTo("SQL_EXECUTION_ERROR");
        assertThat(record.errorMessage()).hasSize(300);
        assertThat(record.rowCount()).isNull();
    }

    @Test
    @DisplayName("历史写入失败不影响已经返回的查询结果")
    void recordSuccess_shouldNotFailQuery_whenInsertFails() {
        doThrow(new RuntimeException("management db down")).when(repository).insert(any());

        assertThatCode(() -> service.recordSuccess(
                new QueryContext(USER_ID, null, null, null), TARGET, SQL, 1,
                new QueryResultResponse(List.of(), List.of(), 0, false)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("分页有更多数据时返回 nextCursor")
    void list_shouldReturnNextCursor_whenMoreRowsExist() {
        when(repository.findPage(USER_ID, 3, null, null, null)).thenReturn(List.of(
                record(3L, NOW), record(2L, NOW.minusMinutes(1)), record(1L, NOW.minusMinutes(2))));

        PageResponse<QueryHistoryResponse> page = service.list(USER_ID, 2, null, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).id()).isEqualTo(3L);
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    @DisplayName("按数据源筛选时把连接 ID 传给仓储")
    void list_shouldFilterByConnection() {
        when(repository.findPage(USER_ID, 21, CONNECTION_ID, null, null)).thenReturn(List.of());

        service.list(USER_ID, 20, null, CONNECTION_ID);

        verify(repository).findPage(USER_ID, 21, CONNECTION_ID, null, null);
    }

    @Test
    @DisplayName("读取他人记录返回 HISTORY_NOT_FOUND")
    void detail_shouldRejectForeignRecord() {
        when(repository.findOwned(9L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(USER_ID, 9L))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.HISTORY_NOT_FOUND);
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("删除未命中返回 HISTORY_NOT_FOUND")
    void delete_shouldRejectMissingRecord() {
        when(repository.delete(9L, USER_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(USER_ID, 9L))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.HISTORY_NOT_FOUND));
    }

    private QueryHistoryRecord captureInsert() {
        ArgumentCaptor<QueryHistoryRecord> captor = ArgumentCaptor.forClass(QueryHistoryRecord.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }

    private static QueryHistoryRecord record(long id, LocalDateTime executedAt) {
        return new QueryHistoryRecord(
                id, USER_ID, null, null, 20L, CONNECTION_ID, "A 商店库", SQL, QueryStatus.SUCCESS,
                executedAt, 12, 1, false, null, null);
    }
}
