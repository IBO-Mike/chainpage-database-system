package com.chainpage.sqlcompiler.lexer;

import java.util.LinkedHashMap;
import java.util.Map;

public record Token(TokenType type, String lexeme, int line, int column) {
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type.name());
        value.put("lexeme", lexeme);
        value.put("line", line);
        value.put("column", column);
        return value;
    }
}
