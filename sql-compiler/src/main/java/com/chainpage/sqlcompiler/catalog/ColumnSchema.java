package com.chainpage.sqlcompiler.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

public record ColumnSchema(String name, String dataType) {
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("dataType", dataType);
        return result;
    }
}
