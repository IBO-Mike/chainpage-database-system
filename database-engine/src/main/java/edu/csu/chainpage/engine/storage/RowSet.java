package edu.csu.chainpage.engine.storage;

import java.util.List;
import java.util.Objects;

// 表示存储引擎返回的带模式行集
public final class RowSet {

    private final List<ColumnSchema> schema; // 行集列结构和列顺序
    private final List<InternalRow> rows; // 行数据

    // 创建行集
    public RowSet(List<ColumnSchema> schema, List<InternalRow> rows) {
        this.schema = List.copyOf(Objects.requireNonNull(schema, "schema cannot be null"));
        this.rows = List.copyOf(Objects.requireNonNull(rows, "rows cannot be null"));
    }

    // 获取行集列结构
    public List<ColumnSchema> getSchema() {
        return schema;
    }

    // 获取行集列结构
    public List<ColumnSchema> schema() {
        return schema;
    }

    // 获取行数据
    public List<InternalRow> getRows() {
        return rows;
    }

    // 获取行数据
    public List<InternalRow> rows() {
        return rows;
    }

    // 获取行数
    public int size() {
        return rows.size();
    }
}
