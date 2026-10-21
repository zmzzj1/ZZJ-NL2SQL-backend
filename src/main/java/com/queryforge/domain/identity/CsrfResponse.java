package com.queryforge.domain.identity;

public record CsrfResponse(String token, String headerName) {
}
