package com.queryforge.query.execution;
import com.queryforge.datasource.repository.TargetConnectionFactory;

import com.queryforge.query.execution.QueryResultResponse;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.shared.error.TargetConnectionException;
import java.sql.Connection;
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
import org.springframework.stereotype.Repository;

/**
 * 在用户选中的只读连接上执行单条查询。每次请求独立建连，不跨用户复用连接。
 */
@Repository
public class TargetQueryRepository {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TargetConnectionFactory connectionFactory;

    public TargetQueryRepository(TargetConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public QueryResultResponse query(
            ConnectionTarget target, String sql, int maxRows, int queryTimeoutSeconds) throws SQLException {
        try (Connection connection = connectionFactory.open(target);
             PreparedStatement statement = connection.prepareStatement(
                     sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setMaxRows(maxRows + 1);
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery()) {
                return extract(resultSet, maxRows);
            }
        }
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
