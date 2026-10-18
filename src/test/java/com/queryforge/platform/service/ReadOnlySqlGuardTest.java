package com.queryforge.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.platform.config.SqlLimits;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("只读 SQL 结构校验")
class ReadOnlySqlGuardTest {

    private final ReadOnlySqlGuard guard = new ReadOnlySqlGuard(new SqlLimits(200, 5, "sql_demo"));

    @Test
    @DisplayName("合法 SELECT 通过并去掉尾部分号")
    void validate_shouldAcceptSelect_whenSingleReadOnlyStatement() {
        assertThat(guard.validate("SELECT COUNT(*) AS total FROM demo_orders;"))
                .isEqualTo("SELECT COUNT(*) AS total FROM demo_orders");
    }

    @Test
    @DisplayName("允许带库名限定与 CTE 的只读查询")
    void validate_shouldAcceptQualifiedTableAndCte() {
        assertThat(guard.validate("SELECT * FROM sql_demo.demo_orders")).isNotBlank();
        assertThat(guard.validate("""
                WITH monthly AS (
                    SELECT DATE_FORMAT(created_at, '%Y-%m') AS month, SUM(amount) AS total
                    FROM demo_orders GROUP BY DATE_FORMAT(created_at, '%Y-%m')
                )
                SELECT * FROM monthly
                """)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE demo_orders SET amount = 0",
            "DELETE FROM demo_orders",
            "DROP TABLE demo_orders",
            "INSERT INTO demo_orders (id) VALUES (9)",
            "SELECT 1; DROP TABLE demo_orders",
            "SELECT * FROM mysql.user",
            "SELECT * FROM other_db.demo_orders",
            "SELECT SLEEP(3)",
            "SELECT BENCHMARK(1000000, MD5('x'))",
            "SELECT * FROM demo_orders FOR UPDATE",
            "SELECT * FROM demo_orders INTO OUTFILE '/tmp/out.txt'"
    })
    @DisplayName("写操作、多语句、系统库、延时函数与加锁查询均被拒绝")
    void validate_shouldRejectUnsafeSql(String sql) {
        assertThatThrownBy(() -> guard.validate(sql))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_NOT_ALLOWED));
    }

    @Test
    @DisplayName("空 SQL 被拒绝")
    void validate_shouldRejectBlankSql() {
        assertThatThrownBy(() -> guard.validate("   "))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SQL_NOT_ALLOWED));
    }

    @Test
    @DisplayName("字符串字面量中的关键字不会导致误判")
    void validate_shouldNotRejectKeywordsInsideLiterals() {
        assertThat(guard.validate("SELECT 'DROP TABLE demo_orders' AS note FROM demo_orders"))
                .isNotBlank();
    }
}
