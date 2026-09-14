package com.chainpage.sqlcompiler.lexer;

import java.util.List;

public final class LexerTest {
    private static int tests;

    public static void main(String[] args) {
        recognizesRequiredTokensAndLocations();
        skipsCommentsAndTracksLines();
        acceptsEscapedQuote();
        reportsIllegalCharacter();
        reportsUnterminatedString();
        reportsUnterminatedComment();
        validatesRequestAndAcceptsEmptySql();
        System.out.println("Lexer tests passed: " + tests);
    }

    private static void recognizesRequiredTokensAndLocations() {
        LexResponse result = lex("select name FROM student WHERE age >= 18;");
        check(result.ok(), "valid SQL should succeed");
        List<Token> tokens = result.tokens();
        assertToken(tokens.get(0), TokenType.KEYWORD, "SELECT", 1, 1);
        assertToken(tokens.get(1), TokenType.IDENTIFIER, "name", 1, 8);
        assertToken(tokens.get(6), TokenType.OPERATOR, ">=", 1, 36);
        assertToken(tokens.get(9), TokenType.EOF, "", 1, 42);
    }

    private static void skipsCommentsAndTracksLines() {
        LexResponse result = lex("-- first\r\nSELECT /* x\n y */ id\nFROM t;");
        check(result.ok(), "comments should be skipped");
        assertToken(result.tokens().get(0), TokenType.KEYWORD, "SELECT", 2, 1);
        assertToken(result.tokens().get(1), TokenType.IDENTIFIER, "id", 3, 7);
        assertToken(result.tokens().get(2), TokenType.KEYWORD, "FROM", 4, 1);
    }

    private static void acceptsEscapedQuote() {
        LexResponse result = lex("INSERT INTO books(title) VALUES ('Tom''s book');");
        check(result.ok(), "escaped quote should succeed");
        Token string = result.tokens().stream()
                .filter(token -> token.type() == TokenType.STRING_LITERAL)
                .findFirst().orElseThrow();
        check(string.lexeme().equals("'Tom''s book'"), "string lexeme must preserve source text");
    }

    private static void reportsIllegalCharacter() {
        LexResponse result = lex("SELECT @;");
        assertError(result, "LEXER_ILLEGAL_CHARACTER", 1, 8);
        check(result.tokens() == null, "failed response must not expose partial tokens");
    }

    private static void reportsUnterminatedString() {
        assertError(lex("SELECT 'abc"), "LEXER_UNTERMINATED_STRING", 1, 8);
    }

    private static void reportsUnterminatedComment() {
        assertError(lex("SELECT /* abc"), "LEXER_UNTERMINATED_COMMENT", 1, 8);
    }

    private static void validatesRequestAndAcceptsEmptySql() {
        assertError(new Lexer().lex(new LexRequest(null)), "LEXER_INVALID_REQUEST", null, null);
        LexResponse empty = lex("");
        check(empty.ok(), "empty SQL is allowed");
        assertToken(empty.tokens().get(0), TokenType.EOF, "", 1, 1);
    }

    private static LexResponse lex(String sql) {
        return new Lexer().lex(new LexRequest(sql));
    }

    private static void assertToken(Token actual, TokenType type, String lexeme, int line, int column) {
        check(actual.equals(new Token(type, lexeme, line, column)), "unexpected token: " + actual);
    }

    private static void assertError(LexResponse response, String code, Integer line, Integer column) {
        check(!response.ok(), "expected failure");
        check(response.error().stage().equals("LEXER"), "wrong error stage");
        check(response.error().code().equals(code), "wrong error code: " + response.error().code());
        check(java.util.Objects.equals(response.error().line(), line), "wrong error line");
        check(java.util.Objects.equals(response.error().column(), column), "wrong error column");
    }

    private static void check(boolean condition, String message) {
        tests++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
