package com.chainpage.sqlcompiler.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

public record CatalogError(
        String stage,
        String code,
        String message,
        Integer line,
        Integer column) {

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage);
        result.put("code", code);
        result.put("message", message);
        result.put("line", line);
        result.put("column", column);
        return result;
    }
}
