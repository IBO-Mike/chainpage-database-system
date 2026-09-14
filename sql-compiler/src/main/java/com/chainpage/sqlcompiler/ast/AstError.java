package com.chainpage.sqlcompiler.ast;

import java.util.LinkedHashMap;
import java.util.Map;

public record AstError(
        String stage,
        String code,
        String message,
        Integer line,
        Integer column) {

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stage", stage);
        value.put("code", code);
        value.put("message", message);
        value.put("line", line);
        value.put("column", column);
        return value;
    }
}
