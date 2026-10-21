package com.queryforge.domain.datasource;

import java.util.List;

public record SchemaResponse(String database, List<SchemaTable> tables) {

    public record SchemaTable(String name, List<SchemaColumn> columns) {
    }

    public record SchemaColumn(String name, String type, boolean nullable) {
    }
}
