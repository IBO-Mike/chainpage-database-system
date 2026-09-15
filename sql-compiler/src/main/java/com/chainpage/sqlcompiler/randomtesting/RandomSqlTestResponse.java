package com.chainpage.sqlcompiler.randomtesting;

import java.util.Map;

public record RandomSqlTestResponse(boolean ok, Map<String, Object> data, Map<String, Object> error) {
    public Map<String, Object> toMap() {
        return ok ? Map.of("ok", true, "data", data) : Map.of("ok", false, "error", error);
    }
}
