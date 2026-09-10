package edu.csu.chainpage.engine.storage;

import java.util.Arrays;
import java.util.Objects;

// 表示页中的一个记录槽位
public final class PageSlot {

    private final byte[] data; // 记录编码后的字节
    private boolean deleted; // 是否已经删除

    // 创建一个页槽位
    public PageSlot(byte[] data, boolean deleted) {
        this.data = Arrays.copyOf(Objects.requireNonNull(data, "data cannot be null"), data.length);
        this.deleted = deleted;
    }

    // 获取记录字节副本
    public byte[] getData() {
        return Arrays.copyOf(data, data.length);
    }

    // 获取记录是否已经删除
    public boolean isDeleted() {
        return deleted;
    }

    // 标记记录已删除
    public void markDeleted() {
        deleted = true;
    }
}
