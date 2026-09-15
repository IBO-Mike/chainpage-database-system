package com.chainpage.sqlcompiler.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record TableSchema(String name, List<ColumnSchema> columns) {
    public TableSchema {
        columns = List.copyOf(columns);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("columns", columns.stream().map(ColumnSchema::toMap).toList());
        return result;
    }
}
