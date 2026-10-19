package com.queryforge.integration.llm;

import java.util.Iterator;
import java.util.stream.Stream;

/**
 * 上游 OpenAI 兼容流的生命周期句柄。关闭即断开连接，供调用方在取消或异常时及时释放资源。
 */
public final class LlmStream implements AutoCloseable {

    private final Stream<String> lines;

    public LlmStream(Stream<String> lines) {
        this.lines = lines;
    }

    public Iterator<String> lineIterator() {
        return lines.iterator();
    }

    @Override
    public void close() {
        lines.close();
    }
}
