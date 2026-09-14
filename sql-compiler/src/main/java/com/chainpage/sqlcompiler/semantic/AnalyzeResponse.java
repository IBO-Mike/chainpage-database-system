package com.chainpage.sqlcompiler.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnalyzeResponse {
    private final List<Map<String, Object>> statements;
    private final SemanticError error;

    private AnalyzeResponse(List<Map<String, Object>> statements, SemanticError error) {
        this.statements = statements == null ? null : List.copyOf(statements);
        this.error = error;
    }

    public static AnalyzeResponse success(List<Map<String, Object>> statements) {
        return new AnalyzeResponse(statements, null);
    }

    public static AnalyzeResponse failure(SemanticError error) {
        return new AnalyzeResponse(null, error);
    }

    public boolean ok() { return error == null; }
    public List<Map<String, Object>> statements() { return statements; }
    public SemanticError error() { return error; }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok());
        if (ok()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("statements", statements);
            data.put("diagnostics", Collections.emptyList());
            result.put("data", data);
        } else result.put("error", error.toMap());
        return result;
    }
}
