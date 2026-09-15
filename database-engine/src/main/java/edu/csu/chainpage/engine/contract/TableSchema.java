package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

// 表示目录快照中的一张表定义
public final class TableSchema {

    private final String name; // 表名
    private final List<ColumnSchema> columns; // 列定义列表

    // 创建表定义
    @JsonCreator
    public TableSchema(
            @JsonProperty("name") String name,
            @JsonProperty("columns") List<ColumnSchema> columns) {
        this.name = name;
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns cannot be null"));
    }

    // 获取表名
    public String getName() {
        return name;
    }

    // 获取列定义列表
    public List<ColumnSchema> getColumns() {
        return columns;
    }
}
