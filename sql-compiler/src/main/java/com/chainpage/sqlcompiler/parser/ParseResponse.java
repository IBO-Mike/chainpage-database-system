package com.chainpage.sqlcompiler.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParseResponse {
    private final List<Map<String, Object>> statements;
    private final ParserError error;

    private ParseResponse(List<Map<String, Object>> statements, ParserError error) {
        this.statements = statements == null ? null : List.copyOf(statements);
        this.error = error;
    }

    public static ParseResponse success(List<Map<String, Object>> statements) {
        return new ParseResponse(statements, null);
    }

    public static ParseResponse failure(ParserError error) {
        return new ParseResponse(null, error);
    }

    public boolean ok() {
        return error == null;
    }

    public List<Map<String, Object>> statements() {
        return statements;
    }

    public ParserError error() {
        return error;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", ok());
        if (ok()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("statements", statements);
            response.put("data", data);
        } else {
            response.put("error", error.toMap());
        }
        return response;
    }
}
