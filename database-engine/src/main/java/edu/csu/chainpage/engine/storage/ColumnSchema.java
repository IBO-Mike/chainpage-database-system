package edu.csu.chainpage.engine.storage;

import java.util.Locale;
import java.util.Objects;

// 表示存储引擎使用的一列表结构
public final class ColumnSchema {

    private final String name; // 列名
    private final String dataType; // 列类型，只允许INT或VARCHAR

    // 创建列结构
    public ColumnSchema(String name, String dataType) {
        this.name = Objects.requireNonNull(name, "name cannot be null").toLowerCase(Locale.ROOT);
        this.dataType = Objects.requireNonNull(dataType, "dataType cannot be null").toUpperCase(Locale.ROOT);
    }

    // 获取列名
    public String getName() {
        return name;
    }

    // 获取已经规范化的列名
    public String normalizedName() {
        return name;
    }

    // 以记录式访问方式获取列名
    public String name() {
        return name;
    }

    // 获取列类型
    public String getDataType() {
        return dataType;
    }

    // 以记录式访问方式获取列类型
    public String dataType() {
        return dataType;
    }
}
