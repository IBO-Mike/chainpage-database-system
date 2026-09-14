package com.chainpage.sqlcompiler.lexer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LexResponse {
    private final List<Token> tokens;
    private final LexerError error;

    private LexResponse(List<Token> tokens, LexerError error) {
        this.tokens = tokens == null ? null : List.copyOf(tokens);
        this.error = error;
    }

    public static LexResponse success(List<Token> tokens) {
        return new LexResponse(tokens, null);
    }

    public static LexResponse failure(LexerError error) {
        return new LexResponse(null, error);
    }

    public boolean ok() {
        return error == null;
    }

    public List<Token> tokens() {
        return tokens;
    }

    public LexerError error() {
        return error;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", ok());
        if (ok()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tokens", tokens.stream().map(Token::toMap).toList());
            response.put("data", data);
        } else {
            response.put("error", error.toMap());
        }
        return response;
    }
}
