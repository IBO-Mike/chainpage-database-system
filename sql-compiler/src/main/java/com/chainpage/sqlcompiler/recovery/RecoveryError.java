package com.chainpage.sqlcompiler.recovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** unexpected 为原 Token 的 lexeme；遇到文件末尾时为 "EOF"。 */
public record RecoveryError(String code, String message, Integer line, Integer column,
                            String unexpected, List<String> expected) {
    public RecoveryError {
        expected = List.copyOf(expected);
    }

    public String stage() { return "PARSER"; }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage());
        result.put("code", code);
        result.put("message", message);
        result.put("line", line);
        result.put("column", column);
        result.put("unexpected", unexpected);
        result.put("expected", expected);
        return result;
    }
}
