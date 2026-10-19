package com.queryforge.platform.service;

import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;

/**
 * 键集分页游标：时间戳（毫秒）+ 主键，避免 offset 分页在新增数据时重复/漏项。
 * 时间按 UTC 序列化，只用于读写往返，不改变数据库中的本地时间值。
 */
record Cursor(LocalDateTime time, long id) {

    static Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int separator = raw.indexOf('_');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw invalid();
        }
        try {
            long millis = Long.parseLong(raw.substring(0, separator));
            long id = Long.parseLong(raw.substring(separator + 1));
            return new Cursor(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC), id);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    String encode() {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli() + "_" + id;
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "cursor 格式无效");
    }
}
