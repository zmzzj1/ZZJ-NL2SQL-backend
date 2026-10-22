package com.queryforge.application.query;

import com.queryforge.application.datasource.ConnectionService;
import com.queryforge.domain.datasource.ConnectionTarget;
import com.queryforge.domain.query.QueryResultResponse;
import com.queryforge.domain.query.ReadOnlySqlGuard;
import com.queryforge.infrastructure.persistence.TargetQueryRepository;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.queryforge.domain.query.QueryHistoryResponse;
import com.queryforge.domain.query.QueryStatus;

/**
 * 查询结果 CSV 导出。结果行不落库，因此导出必须按“重新审批的再次查询”语义执行：
 * 依据历史记录里的 SQL 与 connectionId，重新校验归属、重新过 SQL Guard、重新执行取真实行，
 * 绝不从他人 runId 或任意 SQL 直接导出。单元格以 = + - @ 等公式前缀开头的做防注入转义。
 */
@Service
public class CsvExportService {

    /** 单个单元格最大字节数，超出抛 EXPORT_TOO_LARGE。 */
    private static final int MAX_CELL_BYTES = 32_768;
    /** 整个 CSV 文件最大行数，超出抛 EXPORT_TOO_LARGE。 */
    private static final int MAX_EXPORT_ROWS = 10_000;
    /** 整个 CSV 文件最大字节数，超出抛 EXPORT_TOO_LARGE。 */
    private static final int MAX_EXPORT_BYTES = 5_000_000;

    private final QueryHistoryService historyService;
    private final ConnectionService connectionService;
    private final TargetQueryRepository repository;
    private final ReadOnlySqlGuard guard;

    public CsvExportService(
            QueryHistoryService historyService,
            ConnectionService connectionService,
            TargetQueryRepository repository,
            ReadOnlySqlGuard guard) {
        this.historyService = historyService;
        this.connectionService = connectionService;
        this.repository = repository;
        this.guard = guard;
    }

    /** 返回导出结果；文件名由 Controller 组装。 */
    public CsvExport export(Long userId, Long historyId) {
        QueryHistoryResponse history = historyService.detail(userId, historyId);
        if (history.status() != QueryStatus.SUCCESS || history.connectionId() == null) {
            throw new ApiException(
                    ErrorCode.EXPORT_TOO_LARGE, HttpStatus.UNPROCESSABLE_ENTITY, "仅成功执行的查询可导出");
        }

        ConnectionTarget target = connectionService.target(userId, history.connectionId());
        String sql = guard.validate(history.sql(), target.database());

        QueryResultResponse result;
        try {
            result = repository.query(target, sql, MAX_EXPORT_ROWS, 30);
        } catch (SQLException ex) {
            throw new ApiException(
                    ErrorCode.SQL_EXECUTION_ERROR, HttpStatus.UNPROCESSABLE_ENTITY, "导出时重新执行查询失败");
        }

        String csv = toCsv(result.columns(), result.rows());
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EXPORT_BYTES) {
            throw new ApiException(
                    ErrorCode.EXPORT_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "导出结果超过大小上限，请缩小查询范围");
        }
        String filename = "query-" + historyId + ".csv";
        return new CsvExport(filename, bytes);
    }

    private static String toCsv(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF'); // UTF-8 BOM，便于 Excel 正确识别中文
        appendRow(builder, columns);
        for (Map<String, Object> row : rows) {
            List<String> cells = columns.stream().map((column) -> cellValue(row.get(column))).toList();
            appendRow(builder, cells);
        }
        return builder.toString();
    }

    private static void appendRow(StringBuilder builder, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(escape(cells.get(i)));
        }
        builder.append("\r\n");
    }

    private static String cellValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CELL_BYTES) {
            throw new ApiException(
                    ErrorCode.EXPORT_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "导出单元格内容过大");
        }
        return text;
    }

    /**
     * RFC 4180 转义 + 公式注入防护：以 = + - @ TAB CR 开头的单元格加前缀单引号，
     * 避免被 Excel/WPS 当作公式执行（CSV 注入）。
     */
    static String escape(String value) {
        String body = value;
        boolean needsFormulaGuard = !body.isEmpty()
                && (body.charAt(0) == '=' || body.charAt(0) == '+' || body.charAt(0) == '-'
                        || body.charAt(0) == '@' || body.charAt(0) == '\t' || body.charAt(0) == '\r');
        if (needsFormulaGuard) {
            body = "'" + body;
        }
        boolean needsQuote = body.indexOf(',') >= 0
                || body.indexOf('"') >= 0
                || body.indexOf('\n') >= 0
                || body.indexOf('\r') >= 0;
        if (!needsQuote) {
            return body;
        }
        return '"' + body.replace("\"", "\"\"") + '"';
    }

    public record CsvExport(String filename, byte[] bytes) {
    }
}
