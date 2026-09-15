package com.chainpage.sqlcompiler.recovery;

import com.chainpage.sqlcompiler.extension.*;
import com.chainpage.sqlcompiler.lexer.*;
import com.chainpage.sqlcompiler.parser.*;
import java.util.*;

/** 基础和扩展语法共享的同步边界；segment 保留原始语句序号及 Token 位置。 */
public final class StatementRecovery {
    public record Segment(int statementIndex, List<Map<String, Object>> tokens, ExtensionResponse parsed) {}
    public List<Segment> segments(List<Map<String, Object>> tokens) {
        if (tokens == null || tokens.isEmpty()) throw new IllegalArgumentException("tokens 必须以 EOF 结束");
        for (int i = 0; i < tokens.size(); i++) {
            Map<String, Object> t = tokens.get(i);
            if (!(t.get("type") instanceof String) || !(t.get("lexeme") instanceof String)
                    || !(t.get("line") instanceof Number line) || line.intValue() < 1
                    || !(t.get("column") instanceof Number col) || col.intValue() < 1
                    || "EOF".equals(t.get("type")) != (i == tokens.size() - 1))
                throw new IllegalArgumentException("无效 Token 或 EOF 位置");
        }
        List<Segment> result = new ArrayList<>(); int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Map<String, Object> t = tokens.get(i); boolean eof = "EOF".equals(t.get("type"));
            if (!eof && !("DELIMITER".equals(t.get("type")) && ";".equals(t.get("lexeme")))) continue;
            if (eof && start == i) break;
            List<Map<String, Object>> slice = new ArrayList<>(tokens.subList(start, i + 1));
            if (!eof) { Map<String, Object> next = tokens.get(i + 1); slice.add(Map.of("type", "EOF", "lexeme", "", "line", next.get("line"), "column", next.get("column"))); }
            result.add(new Segment(result.size(), slice, parseOne(slice, false))); start = i + 1;
        }
        return result;
    }
    public static ExtensionResponse parseOne(List<Map<String, Object>> tokens, boolean extensionOnly) {
        if (!extensionOnly) {
            try {
                List<Token> basic = tokens.stream().map(t -> new Token(TokenType.valueOf((String)t.get("type")),
                        (String)t.get("lexeme"), ((Number)t.get("line")).intValue(), ((Number)t.get("column")).intValue())).toList();
                ParseResponse parsed = new Parser().parse(new ParseRequest(basic));
                if (parsed.ok()) return new ExtensionResponse(true, Map.of("statements", parsed.statements()), null);
            } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) { /* 扩展 Token 不属于基础枚举。 */ }
        }
        return new ExtensionParser().parse(Map.of("tokens", tokens));
    }
    public ExtensionResponse parse(Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked") List<Map<String, Object>> tokens = (List<Map<String, Object>>)request.get("tokens");
            List<Map<String, Object>> statements = new ArrayList<>(), errors = new ArrayList<>();
            for (Segment segment : segments(tokens)) {
                if (segment.parsed.ok()) {
                    @SuppressWarnings("unchecked") List<Map<String, Object>> asts = (List<Map<String, Object>>)segment.parsed.data().get("statements"); statements.addAll(asts);
                } else {
                    Map<String, Object> error = new LinkedHashMap<>(segment.parsed.error());
                    error.put("unexpected", segment.tokens.stream().filter(t -> Objects.equals(t.get("line"), error.get("line")) && Objects.equals(t.get("column"), error.get("column")))
                            .map(t -> "EOF".equals(t.get("type")) ? "EOF" : t.get("lexeme")).findFirst().orElse("EOF"));
                    error.putIfAbsent("expected", List.of()); errors.add(error);
                }
            }
            return new ExtensionResponse(true, Map.of("statements", statements, "errors", errors), null);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException | IndexOutOfBoundsException e) {
            Map<String, Object> error = new LinkedHashMap<>(); error.put("stage", "PARSER"); error.put("code", "PARSER_INVALID_REQUEST"); error.put("message", "无效 Token 请求");
            error.put("line", null); error.put("column", null); error.put("unexpected", null); error.put("expected", List.of("EOF"));
            return new ExtensionResponse(false, null, error);
        }
    }
}
