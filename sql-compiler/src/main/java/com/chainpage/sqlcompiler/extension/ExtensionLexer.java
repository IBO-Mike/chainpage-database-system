package com.chainpage.sqlcompiler.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 独立扩展 Token：保留基础字段，并增加 DECIMAL_LITERAL 类型。 */
public final class ExtensionLexer {
    private static final Set<String> KEYWORDS = Set.of("CREATE", "TABLE", "INSERT", "INTO", "VALUES", "SELECT", "FROM",
            "WHERE", "DELETE", "UPDATE", "SET", "AS", "ORDER", "BY", "ASC", "DESC", "GROUP", "HAVING", "JOIN", "INNER",
            "LEFT", "OUTER", "ON", "AND", "OR", "NOT", "NULL", "IS", "TRUE", "FALSE", "INT", "BIGINT", "DECIMAL",
            "VARCHAR", "BOOL", "BOOLEAN", "DATE", "COUNT", "SUM", "AVG", "MIN", "MAX", "NULLS", "FIRST", "LAST",
            "RIGHT", "FULL", "CROSS", "DISTINCT", "LIMIT", "OFFSET", "UNION");
    public ExtensionResponse lex(Map<String, Object> request) {
        return ExtensionResponse.run("LEXER", () -> node("tokens", new Scanner((String) request.get("sql")).scan()));
    }
    private static final class Scanner {
        final String source;
        final List<Map<String, Object>> tokens = new ArrayList<>();
        int index, line = 1, column = 1;
        Scanner(String source) { this.source = java.util.Objects.requireNonNull(source); }
        char peek(int offset) { return index + offset < source.length() ? source.charAt(index + offset) : '\0'; }
        char next() {
            char c = source.charAt(index++);
            if (c == '\n') { line++; column = 1; } else column++;
            return c;
        }
        List<Map<String, Object>> scan() {
            while (index < source.length()) {
                if (Character.isWhitespace(peek(0))) { next(); continue; }
                if (peek(0) == '-' && peek(1) == '-') {
                    while (index < source.length() && peek(0) != '\n') next();
                    continue;
                }
                int start = index, row = line, col = column;
                if (peek(0) == '/' && peek(1) == '*') {
                    next(); next();
                    while (index < source.length() && !(peek(0) == '*' && peek(1) == '/')) next();
                    if (index == source.length()) throw error("UNTERMINATED_COMMENT", "块注释未闭合", row, col);
                    next(); next(); continue;
                }
                String type;
                char c = next();
                if (Character.isLetter(c) || c == '_') {
                    while (Character.isLetterOrDigit(peek(0)) || peek(0) == '_') next();
                    type = KEYWORDS.contains(source.substring(start, index).toUpperCase(Locale.ROOT)) ? "KEYWORD" : "IDENTIFIER";
                } else if (c >= '0' && c <= '9') {
                    while (peek(0) >= '0' && peek(0) <= '9') next();
                    type = "INT_LITERAL";
                    if (peek(0) == '.' && peek(1) >= '0' && peek(1) <= '9') {
                        type = "DECIMAL_LITERAL"; next();
                        while (peek(0) >= '0' && peek(0) <= '9') next();
                    }
                } else if (c == '\'') {
                    boolean closed = false;
                    while (index < source.length()) {
                        if (next() == '\'') {
                            if (peek(0) == '\'') next(); else { closed = true; break; }
                        }
                    }
                    if (!closed) throw error("UNTERMINATED_STRING", "字符串未闭合", row, col);
                    type = "STRING_LITERAL";
                } else if ("(),;.".indexOf(c) >= 0) type = "DELIMITER";
                else if ("+-*/=<>!".indexOf(c) >= 0) {
                    if ((c == '<' || c == '>' || c == '!') && peek(0) == '=') next();
                    else if (c == '<' && peek(0) == '>') next();
                    else if (c == '!') throw error("ILLEGAL_CHARACTER", "! 后必须有 =", row, col);
                    type = "OPERATOR";
                } else throw error("ILLEGAL_CHARACTER", "不支持的字符：" + c, row, col);
                String lexeme = source.substring(start, index);
                tokens.add(node("type", type, "lexeme", type.equals("KEYWORD") ? lexeme.toUpperCase(Locale.ROOT) : lexeme,
                        "line", row, "column", col));
            }
            tokens.add(node("type", "EOF", "lexeme", "", "line", line, "column", column));
            return tokens;
        }
        Failure error(String code, String message, int row, int col) {
            return fail("LEXER", code, message, node("loc", node("line", row, "column", col)));
        }
    }
}
