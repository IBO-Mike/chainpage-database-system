package com.chainpage.sqlcompiler.explain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExplainData {
    private ExplainData() {}
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> nodes(Object value) { return (List<Map<String, Object>>) value; }
    static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String text) || text.isEmpty()) throw new IllegalArgumentException("缺少文本字段：" + key);
        return text;
    }
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put((String) key, copy(child))); return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(); for (Object item : list) result.add(copy(item)); return result;
        }
        return value;
    }
    static Map<String, Object> error(String stage, String code, String message, Map<String, Object> token, List<String> expected) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("stage", stage); error.put("code", code); error.put("message", message);
        error.put("line", token == null ? null : token.get("line"));
        error.put("column", token == null ? null : token.get("column")); error.put("expected", expected);
        return error;
    }
}
