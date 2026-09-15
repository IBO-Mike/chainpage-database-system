package com.chainpage.sqlcompiler.semantic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticError(String stage, String code, String message,
                            Integer line, Integer column, List<String> expected) {
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage);
        result.put("code", code);
        result.put("message", message);
        result.put("line", line);
        result.put("column", column);
        result.put("expected", expected);
        return result;
    }
}
