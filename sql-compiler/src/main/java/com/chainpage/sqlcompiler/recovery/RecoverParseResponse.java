package com.chainpage.sqlcompiler.recovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecoverParseResponse {
    private final List<Map<String, Object>> statements;
    private final List<RecoveryError> errors;
    private final RecoveryError error;

    private RecoverParseResponse(List<Map<String, Object>> statements, List<RecoveryError> errors,
                                 RecoveryError error) {
        this.statements = List.copyOf(statements);
        this.errors = List.copyOf(errors);
        this.error = error;
    }

    public static RecoverParseResponse success(List<Map<String, Object>> statements, List<RecoveryError> errors) {
        return new RecoverParseResponse(statements, errors, null);
    }

    public static RecoverParseResponse failure(RecoveryError error) {
        return new RecoverParseResponse(List.of(), List.of(), error);
    }

    /** true 表示恢复解析已完成；SQL 是否全部合法还需要检查 errors().isEmpty()。 */
    public boolean ok() { return error == null; }
    public List<Map<String, Object>> statements() { return statements; }
    public List<RecoveryError> errors() { return errors; }
    public RecoveryError error() { return error; }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok());
        if (ok()) result.put("data", Map.of("statements", statements,
                "errors", errors.stream().map(RecoveryError::toMap).toList()));
        else result.put("error", error.toMap());
        return result;
    }
}
