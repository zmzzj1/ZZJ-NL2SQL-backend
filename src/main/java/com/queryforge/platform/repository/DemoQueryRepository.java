package com.queryforge.platform.repository;

import com.queryforge.platform.dto.QueryResultResponse;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * 演示库的只读查询入口。动态结果集的列由 SQL 决定，
 * 因此 rows 使用「列名 -> 值」的有序映射，顺序与 columns 一致。
 */
@Repository
public class DemoQueryRepository {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public DemoQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public QueryResultResponse query(String sql, int maxRows, int queryTimeoutSeconds) {
        ResultSetExtractor<QueryResultResponse> extractor = resultSet -> extract(resultSet, maxRows);
        return jdbcTemplate.query(
                connection -> {
                    PreparedStatement statement = connection.prepareStatement(
                            sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                    // 多取一行用于判断是否发生截断，返回给调用方前再裁掉。
                    statement.setMaxRows(maxRows + 1);
                    statement.setQueryTimeout(queryTimeoutSeconds);
                    return statement;
                },
                extractor);
    }

    private static QueryResultResponse extract(ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        Set<String> usedLabels = new HashSet<>();
        for (int index = 1; index <= columnCount; index++) {
            String label = meta.getColumnLabel(index);
            if (label == null || label.isBlank()) {
                label = meta.getColumnName(index);
            }
            columns.add(uniqueLabel(label, usedLabels));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= columnCount; index++) {
                row.put(columns.get(index - 1), convertValue(resultSet.getObject(index)));
            }
            rows.add(row);
        }
        return new QueryResultResponse(List.copyOf(columns), List.copyOf(rows), rows.size(), truncated);
    }

    private static String uniqueLabel(String label, Set<String> used) {
        String candidate = label;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = label + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static Object convertValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(DATE_TIME);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(DATE_TIME);
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }
}
