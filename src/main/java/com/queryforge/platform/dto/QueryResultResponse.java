package com.queryforge.platform.dto;

import java.util.List;
import java.util.Map;

/**
 * 契约 4.3 的成功响应。columns 顺序与每行字段写入顺序一致。
 */
public record QueryResultResponse(
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated
) {
}
