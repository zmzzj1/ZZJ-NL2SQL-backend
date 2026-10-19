package com.queryforge.platform.dto;

import java.util.List;

/**
 * 游标分页响应。nextCursor 为 null 表示没有更多数据。
 */
public record PageResponse<T>(List<T> items, String nextCursor) {
}
