package edu.csu.chainpage.engine.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

// 表示按列名保存的一条逻辑记录
public final class Row {

    private final Map<String, Object> values; // 列名到列值的映射

    // 创建一条记录并规范化列名
    public Row(Map<String, Object> values) {
        Objects.requireNonNull(values, "values cannot be null");
        Map<String, Object> normalizedValues = new LinkedHashMap<>();
        values.forEach((name, value) -> normalizedValues.put(
                Objects.requireNonNull(name, "column name cannot be null")
                        .toLowerCase(Locale.ROOT),
                value
        ));
        this.values = Collections.unmodifiableMap(normalizedValues);
    }

    // 获取指定列的值
    public Object valueOf(String column) {
        return values.get(Objects.requireNonNull(column, "column cannot be null")
                .toLowerCase(Locale.ROOT));
    }

    // 判断记录是否包含指定列
    public boolean contains(String column) {
        return values.containsKey(Objects.requireNonNull(column, "column cannot be null")
                .toLowerCase(Locale.ROOT));
    }

    // 获取不可修改的记录内容
    public Map<String, Object> values() {
        return values;
    }
}
