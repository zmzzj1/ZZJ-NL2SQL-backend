package com.queryforge.tool;

import java.util.Map;

/**
 * 受控内置工具的统一契约：名称、说明、输入 schema、只读属性与执行。
 * 工具名固定白名单，由 ToolRegistry 收集；执行携带归属上下文做服务端授权。
 * P6 只开放只读工具，任何写/副作用能力一律不注册。
 */
public interface Tool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    default boolean readOnly() {
        return true;
    }

    Object execute(ToolInvocation invocation, Map<String, Object> args);
}
