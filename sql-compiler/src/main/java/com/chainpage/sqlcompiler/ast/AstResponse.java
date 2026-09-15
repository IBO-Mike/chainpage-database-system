package com.chainpage.sqlcompiler.ast;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AstResponse {
    private final Map<String, Object> node;
    private final String json;
    private final boolean includeJson;
    private final AstError error;

    private AstResponse(Map<String, Object> node, String json, boolean includeJson, AstError error) {
        this.node = node;
        this.json = json;
        this.includeJson = includeJson;
        this.error = error;
    }

    public static AstResponse made(Map<String, Object> node, String json) {
        return new AstResponse(node, json, true, null);
    }

    public static AstResponse parsed(Map<String, Object> node) {
        return new AstResponse(node, null, false, null);
    }

    public static AstResponse failure(AstError error) {
        return new AstResponse(null, null, false, error);
    }

    public boolean ok() { return error == null; }
    public Map<String, Object> node() { return node; }
    public String json() { return json; }
    public AstError error() { return error; }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok());
        if (ok()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("node", node);
            if (includeJson) data.put("json", json);
            result.put("data", data);
        } else {
            result.put("error", error.toMap());
        }
        return result;
    }
}
