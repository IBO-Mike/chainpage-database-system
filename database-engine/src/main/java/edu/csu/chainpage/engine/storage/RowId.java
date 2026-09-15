package edu.csu.chainpage.engine.storage;

// 表示一条记录在页式存储系统中的位置
public record RowId(int pageId, int slotId) {

    // 校验记录位置中的页号和槽号
    public RowId {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId cannot be negative");
        }
        if (slotId < 0) {
            throw new IllegalArgumentException("slotId cannot be negative");
        }
    }
}
