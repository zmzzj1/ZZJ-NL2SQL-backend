package com.queryforge.datasource.repository;

import com.queryforge.datasource.dto.SchemaResponse;
import com.queryforge.datasource.model.ConnectionTarget;
import com.queryforge.shared.error.TargetConnectionException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 通过 JDBC 元数据读取真实表结构，限制表/列数量，避免超大 Schema 拖垮接口。
 */
@Repository
public class TargetSchemaRepository {

    private static final int MAX_TABLES = 100;
    private static final int MAX_COLUMNS_PER_TABLE = 80;

    private final TargetConnectionFactory connectionFactory;

    public TargetSchemaRepository(TargetConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public SchemaResponse read(ConnectionTarget target) {
        try (Connection connection = connectionFactory.open(target)) {
            DatabaseMetaData metaData = connection.getMetaData();
            List<SchemaResponse.SchemaTable> tables = new ArrayList<>();
            try (ResultSet tableRows = metaData.getTables(
                    target.database(), null, "%", new String[]{"TABLE", "VIEW"})) {
                while (tableRows.next() && tables.size() < MAX_TABLES) {
                    String tableName = tableRows.getString("TABLE_NAME");
                    if (tableName == null || tableName.isBlank()) {
                        continue;
                    }
                    tables.add(new SchemaResponse.SchemaTable(
                            tableName, readColumns(metaData, target.database(), tableName)));
                }
            }
            tables.sort(Comparator.comparing(SchemaResponse.SchemaTable::name));
            return new SchemaResponse(target.database(), List.copyOf(tables));
        } catch (TargetConnectionException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new TargetConnectionException("读取数据库 Schema 失败", ex);
        }
    }

    private static List<SchemaResponse.SchemaColumn> readColumns(
            DatabaseMetaData metaData, String database, String tableName) throws SQLException {
        List<SchemaResponse.SchemaColumn> columns = new ArrayList<>();
        try (ResultSet columnRows = metaData.getColumns(database, null, tableName, "%")) {
            while (columnRows.next() && columns.size() < MAX_COLUMNS_PER_TABLE) {
                columns.add(new SchemaResponse.SchemaColumn(
                        columnRows.getString("COLUMN_NAME"),
                        columnRows.getString("TYPE_NAME"),
                        columnRows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
            }
        }
        return List.copyOf(columns);
    }
}
