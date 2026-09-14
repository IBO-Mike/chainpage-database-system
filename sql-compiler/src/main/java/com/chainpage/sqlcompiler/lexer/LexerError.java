package com.chainpage.sqlcompiler.lexer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LexerError(
        String stage,
        String code,
        String message,
        Integer line,
        Integer column,
        List<String> expected) {

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stage", stage);
        value.put("code", code);
        value.put("message", message);
        value.put("line", line);
        value.put("column", column);
        value.put("expected", expected);
        return value;
    }
}
