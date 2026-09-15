package com.chainpage.sqlcompiler.extension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SqlTree {
    private SqlTree() {}
    static Map<String, Object> node(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> nodes(Object value) { return (List<Map<String, Object>>) value; }
    static List<String> strings(Object value) { return ((List<?>) value).stream().map(String.class::cast).toList(); }
    static String text(Map<String, Object> value, String key) {
        String result = (String) value.get(key);
        if (result == null || result.isEmpty()) throw new IllegalArgumentException(key);
        return result;
    }
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put((String) key, copy(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(copy(item)));
            return result;
        }
        return value;
    }
    static Failure fail(String stage, String code, String message, Map<String, Object> owner) {
        Map<String, Object> loc = owner == null ? null : map(owner.get("loc"));
        return new Failure(node("stage", stage, "code", stage + "_" + code, "message", message,
                "line", loc == null ? null : loc.get("line"), "column", loc == null ? null : loc.get("column"), "expected", List.of()));
    }
    static final class Failure extends RuntimeException {
        final Map<String, Object> error;
        Failure(Map<String, Object> error) { super((String) error.get("message")); this.error = error; }
    }
}
