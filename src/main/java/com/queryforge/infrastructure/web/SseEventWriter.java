package com.queryforge.infrastructure.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 手写 SSE 帧，确保 event/data/空行格式与契约逐字节一致，并在每帧后 flush。
 */
public final class SseEventWriter {

    private final OutputStream outputStream;
    private final ObjectMapper objectMapper;

    public SseEventWriter(OutputStream outputStream, ObjectMapper objectMapper) {
        this.outputStream = outputStream;
        this.objectMapper = objectMapper;
    }

    public void event(String name, Object payload) throws IOException {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IOException("无法序列化 SSE 数据", ex);
        }
        outputStream.write(("event: " + name + "\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }
}
