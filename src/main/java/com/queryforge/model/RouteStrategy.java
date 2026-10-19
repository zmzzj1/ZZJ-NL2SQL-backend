package com.queryforge.model;

import java.util.List;

/** O2 路由策略。 */
public enum RouteStrategy {
    ROUND_ROBIN,
    LATENCY_FIRST,
    SUCCESS_RATE_FIRST,
    SMART_WEIGHTED;

    public static RouteStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return ROUND_ROBIN;
        }
        return valueOf(value.trim().toUpperCase());
    }

    public static List<Long> parseIds(String json) {
        return com.queryforge.model.JsonIds.parse(json);
    }
}
