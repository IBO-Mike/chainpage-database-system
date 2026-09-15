package edu.csu.chainpage.engine.storage;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

// 表示存储引擎使用的一张表结构
public final class TableSchema {

    private final String name; // 表名
    private final List<ColumnSchema> columns; // 列结构列表

    // 创建表结构
    public TableSchema(String name, List<ColumnSchema> columns) {
        this.name = Objects.requireNonNull(name, "name cannot be null").toLowerCase(Locale.ROOT);
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns cannot be null"));
    }

    // 获取表名
    public String getName() {
        return name;
    }

    // 获取已经规范化的表名
    public String normalizedName() {
        return name;
    }

    // 以记录式访问方式获取表名
    public String name() {
        return name;
    }

    // 获取列结构列表
    public List<ColumnSchema> getColumns() {
        return columns;
    }

    // 以记录式访问方式获取列结构列表
    public List<ColumnSchema> columns() {
        return columns;
    }

    // 按列名查找列结构
    public ColumnSchema findColumn(String columnName) {
        String normalizedName = Objects.requireNonNull(columnName, "columnName cannot be null")
                .toLowerCase(Locale.ROOT);
        return columns.stream()
                .filter(column -> column.getName().equals(normalizedName))
                .findFirst()
                .orElse(null);
    }

    // 按名称取得列结构；列不存在时返回null
    public ColumnSchema column(String columnName) {
        return findColumn(columnName);
    }
}
