package com.queryforge.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.queryforge.platform.config.SqlLimits;
import com.queryforge.platform.dto.QueryResultResponse;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import com.queryforge.platform.repository.DemoQueryRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("SQL 执行服务")
class SqlExecutionServiceTest {

    private static final int MAX_ROWS = 200;
    private static final int TIMEOUT_SECONDS = 5;

    @Mock
    private DemoQueryRepository repository;

    private SqlExecutionService service;

    @BeforeEach
    void setUp() {
        SqlLimits limits = new SqlLimits(MAX_ROWS, TIMEOUT_SECONDS, "sql_demo");
        service = new SqlExecutionService(repository, new ReadOnlySqlGuard(limits), limits);
    }

    @Test
    @DisplayName("合法 SELECT 交由仓库执行并原样返回结果")
    void execute_shouldDelegateToRepository_whenSqlAllowed() {
        QueryResultResponse expected = new QueryResultResponse(
                List.of("total"), List.of(Map.of("total", 3)), 1, false);
        when(repository.query("SELECT COUNT(*) AS total FROM demo_orders", MAX_ROWS, TIMEOUT_SECONDS))
                .thenReturn(expected);

        QueryResultResponse actual = service.execute("SELECT COUNT(*) AS total FROM demo_orders;");

        assertThat(actual).isEqualTo(expected);
        verify(repository).query("SELECT COUNT(*) AS total FROM demo_orders", MAX_ROWS, TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("危险 SQL 在校验阶段被拒绝，不会访问数据库")
    void execute_shouldRejectBeforeRepository_whenSqlIsWrite() {
        assertThatThrownBy(() -> service.execute("DELETE FROM demo_orders"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_NOT_ALLOWED));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("数据库连不上时返回 SERVICE_UNAVAILABLE，不泄露连接串")
    void execute_shouldMapConnectionFailure() {
        when(repository.query("SELECT * FROM demo_orders", MAX_ROWS, TIMEOUT_SECONDS))
                .thenThrow(new DataAccessResourceFailureException("connection refused: jdbc:mysql://secret"));

        assertThatThrownBy(() -> service.execute("SELECT * FROM demo_orders"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(ex.getStatus().value()).isEqualTo(503);
                    assertThat(ex.getMessage()).contains("演示数据库暂不可用").doesNotContain("jdbc:mysql", "secret");
                });
    }

    @Test
    @DisplayName("查询超时返回可理解的 422 提示")
    void execute_shouldMapQueryTimeout() {
        when(repository.query("SELECT * FROM demo_orders", MAX_ROWS, TIMEOUT_SECONDS))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> service.execute("SELECT * FROM demo_orders"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_EXECUTION_ERROR);
                    assertThat(ex.getMessage()).contains("查询超时");
                });
    }

    @Test
    @DisplayName("其他数据库错误保持 SQL_EXECUTION_ERROR 且不泄露底层细节")
    void execute_shouldMapOtherDataAccessException() {
        when(repository.query("SELECT * FROM demo_orders", MAX_ROWS, TIMEOUT_SECONDS))
                .thenThrow(new DataAccessException("bad sql: jdbc:mysql://secret") {
                });

        assertThatThrownBy(() -> service.execute("SELECT * FROM demo_orders"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_EXECUTION_ERROR);
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getMessage()).doesNotContain("jdbc:mysql", "secret");
                });
    }
}
