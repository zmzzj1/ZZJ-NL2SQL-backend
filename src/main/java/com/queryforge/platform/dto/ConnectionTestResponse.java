package com.queryforge.platform.dto;

public record ConnectionTestResponse(boolean success, String message, long latencyMs) {
}
