package com.queryforge.datasource.dto;

public record ConnectionTestResponse(boolean success, String message, long latencyMs) {
}
