package edu.csu.chainpage.engine.executor.extended;

import java.util.Locale;
import java.util.Objects;

// 表示分组计划中的一个聚合函数定义
public final class AggregateSpec {

    private final String function; // 聚合函数，只允许COUNT或SUM
    private final String column; // 目标列名，COUNT可以使用星号
    private final String alias; // 聚合结果列名

    // 创建聚合定义
    public AggregateSpec(String function, String column, String alias) {
        String normalizedFunction = Objects.requireNonNull(function, "function cannot be null")
                .toUpperCase(Locale.ROOT);
        String normalizedColumn = Objects.requireNonNull(column, "column cannot be null")
                .toLowerCase(Locale.ROOT);
        String normalizedAlias = Objects.requireNonNull(alias, "alias cannot be null")
                .toLowerCase(Locale.ROOT);
        if (!"COUNT".equals(normalizedFunction) && !"SUM".equals(normalizedFunction)) {
            throw new IllegalArgumentException("function must be COUNT or SUM");
        }
        if (normalizedColumn.isBlank()) {
            throw new IllegalArgumentException("column cannot be blank");
        }
        if (normalizedAlias.isBlank()) {
            throw new IllegalArgumentException("alias cannot be blank");
        }
        if ("SUM".equals(normalizedFunction) && "*".equals(normalizedColumn)) {
            throw new IllegalArgumentException("SUM cannot use *");
        }
        this.function = normalizedFunction;
        this.column = normalizedColumn;
        this.alias = normalizedAlias;
    }

    // 返回聚合函数名
    public String function() {
        return function;
    }

    // 返回聚合目标列
    public String column() {
        return column;
    }

    // 返回聚合结果列名
    public String alias() {
        return alias;
    }
}
