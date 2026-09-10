package edu.csu.chainpage.engine.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 表示存储引擎在内存中解析的一页数据
public final class DataPage {

    public static final int PAGE_SIZE = 4096;
    public static final int HEADER_SIZE = Integer.BYTES * 2;
    public static final int SLOT_HEADER_SIZE = 1 + Integer.BYTES;

    private final List<PageSlot> slots; // 页内记录槽位

    // 创建空数据页
    public DataPage() {
        this.slots = new ArrayList<>();
    }

    // 根据已有槽位创建数据页
    public DataPage(List<PageSlot> slots) {
        this.slots = new ArrayList<>(Objects.requireNonNull(slots, "slots cannot be null"));
    }

    // 获取当前页的剩余空间
    public int freeBytes() {
        int usedBytes = HEADER_SIZE;
        for (PageSlot slot : slots) {
            usedBytes += SLOT_HEADER_SIZE + slot.getData().length;
        }
        return PAGE_SIZE - usedBytes;
    }

    // 判断当前页是否能够容纳指定长度的记录
    public boolean canFit(int recordLength) {
        return recordLength >= 0 && freeBytes() >= SLOT_HEADER_SIZE + recordLength;
    }

    // 向页中追加一条记录
    public void appendRecord(byte[] record) {
        Objects.requireNonNull(record, "record cannot be null");
        if (!canFit(record.length)) {
            throw new IllegalArgumentException("record does not fit in page");
        }
        slots.add(new PageSlot(record, false));
    }

    // 标记指定槽位已经删除
    public void deleteSlot(int slotId) {
        if (slotId < 0 || slotId >= slots.size()) {
            throw new IllegalArgumentException("slotId is out of range");
        }
        slots.get(slotId).markDeleted();
    }

    // 获取页内槽位
    public List<PageSlot> slots() {
        return List.copyOf(slots);
    }
}
