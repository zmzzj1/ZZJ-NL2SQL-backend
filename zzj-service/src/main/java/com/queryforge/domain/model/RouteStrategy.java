package com.queryforge.domain.model;

import java.util.List;

/** O2 路由策略。P12 S4：SESSION_AFFINITY 为会话亲和性命中（非持久化策略值）。 */
public enum RouteStrategy {
    ROUND_ROBIN,
    LATENCY_FIRST,
    SUCCESS_RATE_FIRST,
    SMART_WEIGHTED,
    SESSION_AFFINITY;

    public static RouteStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return ROUND_ROBIN;
        }
        return valueOf(value.trim().toUpperCase());
    }

    public static List<Long> parseIds(String json) {
        return com.queryforge.domain.model.JsonIds.parse(json);
    }
}
