package edu.csu.chainpage.engine.lifecycle;

import java.util.List;
import java.util.Objects;

// 表示数据库关闭后的结果
public final class ShutdownResult {

    private final List<Integer> flushedPages; // 已经刷回的页号
    private final boolean closed; // 数据库是否已经关闭

    // 创建关闭结果
    public ShutdownResult(List<Integer> flushedPages, boolean closed) {
        this.flushedPages = List.copyOf(Objects.requireNonNull(flushedPages, "flushedPages cannot be null"));
        this.closed = closed;
    }

    // 获取已经刷回的页号
    public List<Integer> getFlushedPages() {
        return flushedPages;
    }

    // 获取数据库是否已经关闭
    public boolean isClosed() {
        return closed;
    }
}
