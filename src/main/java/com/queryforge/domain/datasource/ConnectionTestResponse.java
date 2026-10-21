package com.queryforge.domain.datasource;

public record ConnectionTestResponse(boolean success, String message, long latencyMs) {
}
