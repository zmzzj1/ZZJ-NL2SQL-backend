package com.queryforge.platform.exception;

/**
 * 稳定的对外错误码。前端按 code 决定提示文案，不解析后端自然语言。
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    UNAUTHENTICATED,
    FORBIDDEN,
    DUPLICATE_EMAIL,
    CONNECTION_NOT_FOUND,
    CONNECTION_FAILED,
    SQL_NOT_ALLOWED,
    SQL_EXECUTION_ERROR,
    MODEL_NOT_CONFIGURED,
    LLM_UPSTREAM_ERROR,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
