package edu.csu.chainpage.engine.storage;

import java.util.Objects;

// 表示执行器内部使用的带物理位置记录
public final class InternalRow {

    private final RowId rowId; // 记录物理位置
    private final Row values; // 记录逻辑值

    // 创建内部记录
    public InternalRow(RowId rowId, Row values) {
        this.rowId = Objects.requireNonNull(rowId, "rowId cannot be null");
        this.values = Objects.requireNonNull(values, "values cannot be null");
    }

    // 获取记录物理位置
    public RowId getRowId() {
        return rowId;
    }

    // 获取记录物理位置
    public RowId rowId() {
        return rowId;
    }

    // 获取记录逻辑值
    public Row getValues() {
        return values;
    }

    // 获取记录逻辑值
    public Row values() {
        return values;
    }
}
