package edu.csu.chainpage.engine.executor.extended;

import java.util.Locale;
import java.util.Objects;

// 表示排序计划中的一个列和方向
public final class SortKey {

    private final String column; // 已规范化的排序列名
    private final String direction; // 排序方向，只允许ASC或DESC

    // 创建排序键
    public SortKey(String column, String direction) {
        String normalizedColumn = Objects.requireNonNull(column, "column cannot be null")
                .toLowerCase(Locale.ROOT);
        String normalizedDirection = Objects.requireNonNull(direction, "direction cannot be null")
                .toUpperCase(Locale.ROOT);
        if (normalizedColumn.isBlank()) {
            throw new IllegalArgumentException("column cannot be blank");
        }
        if (!"ASC".equals(normalizedDirection) && !"DESC".equals(normalizedDirection)) {
            throw new IllegalArgumentException("direction must be ASC or DESC");
        }
        this.column = normalizedColumn;
        this.direction = normalizedDirection;
    }

    // 返回排序列名
    public String column() {
        return column;
    }

    // 返回是否按降序排列
    public boolean descending() {
        return "DESC".equals(direction);
    }
}
