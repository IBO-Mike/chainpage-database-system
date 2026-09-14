package com.chainpage.sqlcompiler.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Lexer {
    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "CREATE", "TABLE", "INSERT", "INTO",
            "VALUES", "DELETE", "INT", "VARCHAR", "AND", "OR", "NOT");
    private static final Set<Character> SINGLE_OPERATORS = Set.of('=', '>', '<', '+', '-', '*', '/');
    private static final Set<Character> DELIMITERS = Set.of('(', ')', ',', ';');

    private String source;
    private int index;
    private int line;
    private int column;
    private final List<Token> tokens = new ArrayList<>();

    public synchronized LexResponse lex(LexRequest request) {
        if (request == null || request.sql() == null) {
            return failure("LEXER_INVALID_REQUEST", "请求必须包含非 null 的 sql 字符串", null, null,
                    List.of("{\"sql\": string}"));
        }

        source = request.sql();
        index = 0;
        line = 1;
        column = 1;
        tokens.clear();

        while (!atEnd()) {
            char current = peek();
            if (isWhitespace(current)) {
                advance();
            } else if (current == '-' && peekNext() == '-') {
                skipLineComment();
            } else if (current == '/' && peekNext() == '*') {
                LexResponse error = skipBlockComment();
                if (error != null) {
                    return error;
                }
            } else if (isIdentifierStart(current)) {
                scanIdentifier();
            } else if (isAsciiDigit(current)) {
                scanInteger();
            } else if (current == '\'') {
                LexResponse error = scanString();
                if (error != null) {
                    return error;
                }
            } else {
                LexResponse error = scanSymbol();
                if (error != null) {
                    return error;
                }
            }
        }

        tokens.add(new Token(TokenType.EOF, "", line, column));
        return LexResponse.success(tokens);
    }

    private void scanIdentifier() {
        int start = index;
        int startLine = line;
        int startColumn = column;
        advance();
        while (!atEnd() && isIdentifierPart(peek())) {
            advance();
        }
        String raw = source.substring(start, index);
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (KEYWORDS.contains(normalized)) {
            tokens.add(new Token(TokenType.KEYWORD, normalized, startLine, startColumn));
        } else {
            tokens.add(new Token(TokenType.IDENTIFIER, raw, startLine, startColumn));
        }
    }

    private void scanInteger() {
        int start = index;
        int startLine = line;
        int startColumn = column;
        while (!atEnd() && isAsciiDigit(peek())) {
            advance();
        }
        tokens.add(new Token(TokenType.INT_LITERAL, source.substring(start, index), startLine, startColumn));
    }

    private LexResponse scanString() {
        int start = index;
        int startLine = line;
        int startColumn = column;
        advance();
        while (!atEnd()) {
            if (peek() == '\'') {
                advance();
                if (!atEnd() && peek() == '\'') {
                    advance();
                    continue;
                }
                tokens.add(new Token(TokenType.STRING_LITERAL, source.substring(start, index), startLine, startColumn));
                return null;
            }
            advance();
        }
        return failure("LEXER_UNTERMINATED_STRING", "字符串字面量未闭合", startLine, startColumn,
                List.of("'"));
    }

    private LexResponse scanSymbol() {
        int startLine = line;
        int startColumn = column;
        char current = peek();
        char next = peekNext();
        if ((current == '!' && next == '=') || (current == '>' && next == '=')
                || (current == '<' && next == '=')) {
            advance();
            advance();
            tokens.add(new Token(TokenType.OPERATOR, "" + current + next, startLine, startColumn));
            return null;
        }
        if (SINGLE_OPERATORS.contains(current)) {
            advance();
            tokens.add(new Token(TokenType.OPERATOR, String.valueOf(current), startLine, startColumn));
            return null;
        }
        if (DELIMITERS.contains(current)) {
            advance();
            tokens.add(new Token(TokenType.DELIMITER, String.valueOf(current), startLine, startColumn));
            return null;
        }
        return failure("LEXER_ILLEGAL_CHARACTER", "非法字符：" + current, startLine, startColumn,
                List.of("标识符", "整数", "字符串", "运算符", "分隔符"));
    }

    private void skipLineComment() {
        advance();
        advance();
        while (!atEnd() && peek() != '\n' && peek() != '\r') {
            advance();
        }
    }

    private LexResponse skipBlockComment() {
        int startLine = line;
        int startColumn = column;
        advance();
        advance();
        while (!atEnd()) {
            if (peek() == '*' && peekNext() == '/') {
                advance();
                advance();
                return null;
            }
            advance();
        }
        return failure("LEXER_UNTERMINATED_COMMENT", "多行注释未闭合", startLine, startColumn,
                List.of("*/"));
    }

    private LexResponse failure(String code, String message, Integer errorLine, Integer errorColumn,
                                List<String> expected) {
        return LexResponse.failure(new LexerError("LEXER", code, message, errorLine, errorColumn, expected));
    }

    private char advance() {
        char value = source.charAt(index++);
        if (value == '\r') {
            if (!atEnd() && source.charAt(index) == '\n') {
                index++;
            }
            line++;
            column = 1;
        } else if (value == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return value;
    }

    private char peek() {
        return source.charAt(index);
    }

    private char peekNext() {
        return index + 1 < source.length() ? source.charAt(index + 1) : '\0';
    }

    private boolean atEnd() {
        return index >= source.length();
    }

    private static boolean isWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
    }

    private static boolean isIdentifierStart(char value) {
        return isAsciiLetter(value) || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || isAsciiDigit(value);
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }
}
