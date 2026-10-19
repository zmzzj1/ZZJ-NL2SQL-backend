package com.queryforge.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 内置只读工具：返回当前授权数据源的表结构描述（服务端读取，不信任前端）。
 * 工具名固定白名单，参数带 connectionId，由服务端校验归属后读取。
 */
@Component
public class DescribeSchemaTool implements Tool {

    private final com.queryforge.datasource.ConnectionService connectionService;

    public DescribeSchemaTool(com.queryforge.datasource.ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @Override
    public String name() {
        return "describe_schema";
    }

    @Override
    public String description() {
        return "读取当前用户授权数据源的表结构（表名与列），只读。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("connectionId", Map.of("type", "integer"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("connectionId"));
    }

    @Override
    public Object execute(ToolInvocation invocation, Map<String, Object> args) {
        Long connectionId = ((Number) args.get("connectionId")).longValue();
        var schema = connectionService.schema(invocation.userId(), connectionId);
        List<Map<String, Object>> tables = schema.tables().stream()
                .map(table -> Map.<String, Object>of(
                        "name", table.name(),
                        "columns", table.columns().stream()
                                .map(column -> Map.of("name", column.name(), "type", column.type()))
                                .toList()))
                .toList();
        return Map.of("database", schema.database(), "tables", tables);
    }
}
