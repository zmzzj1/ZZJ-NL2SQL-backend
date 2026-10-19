package com.queryforge.identity;

public record CsrfResponse(String token, String headerName) {
}
