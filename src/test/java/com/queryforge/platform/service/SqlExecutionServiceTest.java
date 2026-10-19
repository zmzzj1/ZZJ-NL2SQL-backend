package com.queryforge.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.queryforge.platform.config.SqlLimits;
import com.queryforge.platform.dto.ExecuteSqlRequest;
import com.queryforge.platform.dto.QueryResultResponse;
import com.queryforge.platform.entity.ConnectionTarget;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import com.queryforge.platform.exception.TargetConnectionException;
import com.queryforge.platform.repository.TargetQueryRepository;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SQL 执行服务")
class SqlExecutionServiceTest {

    private static final long USER_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final long CONVERSATION_ID = 20L;
    private static final int MAX_ROWS = 200;
    private static final int TIMEOUT_SECONDS = 5;
    private static final ConnectionTarget TARGET = new ConnectionTarget(
            CONNECTION_ID, "A 商店库", "127.0.0.1", 3306, "sql_demo_a", "demo_a_reader", "secret");

    @Mock
    private ConnectionService connectionService;

    @Mock
    private TargetQueryRepository repository;

    @Mock
    private ConversationService conversationService;

    @Mock
    private QueryHistoryService historyService;

    private SqlExecutionService service;

    @BeforeEach
    void setUp() {
        SqlLimits limits = new SqlLimits(MAX_ROWS, TIMEOUT_SECONDS);
        service = new SqlExecutionService(
                connectionService, repository, new ReadOnlySqlGuard(), limits, conversationService, historyService);
    }

    @Test
    @DisplayName("合法 SELECT 在选中连接上执行并写入成功历史")
    void execute_shouldRunOnSelectedConnection() throws Exception {
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);
        when(conversationService.requireMatchingConversation(USER_ID, CONVERSATION_ID, CONNECTION_ID))
                .thenReturn(CONVERSATION_ID);
        QueryResultResponse expected = new QueryResultResponse(
                List.of("订单数"), List.of(Map.of("订单数", 3)), 1, false);
        when(repository.query(TARGET, "SELECT COUNT(*) AS `订单数` FROM shop_orders", MAX_ROWS, TIMEOUT_SECONDS))
                .thenReturn(expected);

        ExecuteSqlRequest request = new ExecuteSqlRequest(
                CONNECTION_ID, CONVERSATION_ID, "SELECT COUNT(*) AS `订单数` FROM shop_orders;");
        QueryResultResponse actual = service.execute(USER_ID, request);

        assertThat(actual).isEqualTo(expected);
        verify(repository).query(TARGET, "SELECT COUNT(*) AS `订单数` FROM shop_orders", MAX_ROWS, TIMEOUT_SECONDS);
        verify(historyService).recordSuccess(
                eq(new QueryContext(USER_ID, CONVERSATION_ID, null, null)), eq(TARGET),
                eq("SELECT COUNT(*) AS `订单数` FROM shop_orders"), anyLong(), eq(expected));
    }

    @Test
    @DisplayName("危险 SQL 在访问数据库前被拒绝，且不写查询历史")
    void execute_shouldRejectBeforeRepository_whenSqlIsWrite() {
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);

        ExecuteSqlRequest request = new ExecuteSqlRequest(CONNECTION_ID, CONVERSATION_ID, "DELETE FROM shop_orders");
        assertThatThrownBy(() -> service.execute(USER_ID, request))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_NOT_ALLOWED));
        verifyNoInteractions(repository);
        verifyNoInteractions(historyService);
    }

    @Test
    @DisplayName("跨库查询被当前连接库限制拒绝")
    void execute_shouldRejectCrossDatabaseQuery() {
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);

        ExecuteSqlRequest request = new ExecuteSqlRequest(
                CONNECTION_ID, CONVERSATION_ID, "SELECT COUNT(*) FROM sql_demo_b.blog_posts");
        assertThatThrownBy(() -> service.execute(USER_ID, request))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_NOT_ALLOWED));
        verifyNoInteractions(repository);
        verifyNoInteractions(historyService);
    }

    @Test
    @DisplayName("目标库连接失败返回 CONNECTION_FAILED，并记录失败历史")
    void execute_shouldMapConnectionFailure() throws Exception {
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);
        when(conversationService.requireMatchingConversation(USER_ID, null, CONNECTION_ID)).thenReturn(null);
        when(repository.query(TARGET, "SELECT 1", MAX_ROWS, TIMEOUT_SECONDS))
                .thenThrow(new TargetConnectionException("连接失败", new SQLException("access denied for secret")));

        ExecuteSqlRequest request = new ExecuteSqlRequest(CONNECTION_ID, null, "SELECT 1");
        assertThatThrownBy(() -> service.execute(USER_ID, request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONNECTION_FAILED);
                    assertThat(ex.getStatus().value()).isEqualTo(502);
                    assertThat(ex.getMessage()).doesNotContain("secret");
                });
        verify(historyService).recordFailure(
                eq(new QueryContext(USER_ID, null, null, null)), eq(TARGET), eq("SELECT 1"), anyLong(),
                eq(ErrorCode.CONNECTION_FAILED), eq("无法连接该数据源，请检查连接配置"));
    }

    @Test
    @DisplayName("查询超时返回可理解的 422 提示，并记录失败历史")
    void execute_shouldMapQueryTimeout() throws Exception {
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);
        when(conversationService.requireMatchingConversation(USER_ID, null, CONNECTION_ID)).thenReturn(null);
        when(repository.query(TARGET, "SELECT 1", MAX_ROWS, TIMEOUT_SECONDS))
                .thenThrow(new SQLTimeoutException("timeout"));

        ExecuteSqlRequest request = new ExecuteSqlRequest(CONNECTION_ID, null, "SELECT 1");
        assertThatThrownBy(() -> service.execute(USER_ID, request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_EXECUTION_ERROR);
                    assertThat(ex.getMessage()).contains("查询超时");
                });
        verify(historyService).recordFailure(
                eq(new QueryContext(USER_ID, null, null, null)), eq(TARGET), eq("SELECT 1"), anyLong(),
                eq(ErrorCode.SQL_EXECUTION_ERROR), eq("查询超时，已被服务端终止"));
    }

    @Test
    @DisplayName("其他 SQL 错误保持 422 且不泄露底层细节")
    void execute_shouldMapOtherSqlException() throws Exception {
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);
        when(repository.query(TARGET, "SELECT 1", MAX_ROWS, TIMEOUT_SECONDS))
                .thenThrow(new SQLException("Table 'secret' doesn't exist"));

        ExecuteSqlRequest request = new ExecuteSqlRequest(CONNECTION_ID, null, "SELECT 1");
        assertThatThrownBy(() -> service.execute(USER_ID, request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_EXECUTION_ERROR);
                    assertThat(ex.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    @DisplayName("会话与数据源不一致时在访问数据库前拒绝")
    void execute_shouldRejectMismatchedConversation() {
        when(connectionService.target(USER_ID, CONNECTION_ID)).thenReturn(TARGET);
        when(conversationService.requireMatchingConversation(USER_ID, CONVERSATION_ID, CONNECTION_ID))
                .thenThrow(new ApiException(
                        ErrorCode.VALIDATION_ERROR, org.springframework.http.HttpStatus.BAD_REQUEST,
                        "会话与当前数据源不一致"));

        ExecuteSqlRequest request = new ExecuteSqlRequest(CONNECTION_ID, CONVERSATION_ID, "SELECT 1");
        assertThatThrownBy(() -> service.execute(USER_ID, request))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(repository);
        verifyNoInteractions(historyService);
    }
}
