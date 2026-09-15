package com.chainpage.sqlcompiler.parser;

import com.chainpage.sqlcompiler.CompilerFrontEnd;

import java.util.List;
import java.util.Map;

public final class ParserTest {
    private static int checks;

    public static void main(String[] args) {
        parsesAllStatementKinds();
        preservesExpressionPrecedence();
        parsesMultipleStatements();
        decodesStringLiteral();
        reportsSyntaxLocationAndExpected();
        connectsLexerErrors();
        System.out.println("Parser LL(1) tests passed: " + checks);
    }

    private static void parsesAllStatementKinds() {
        ParseResponse response = parse("CREATE TABLE student(id INT,name VARCHAR);"
                + "INSERT INTO student(id,name) VALUES (1,'Alice');"
                + "SELECT * FROM student;DELETE FROM student WHERE id=1;");
        check(response.ok(), "four valid statements should parse");
        check(response.statements().size() == 4, "statement count");
        check(kind(response, 0).equals("CreateTableStmt"), "create kind");
        check(kind(response, 1).equals("InsertStmt"), "insert kind");
        check(kind(response, 2).equals("SelectStmt"), "select kind");
        check(kind(response, 3).equals("DeleteStmt"), "delete kind");
    }

    @SuppressWarnings("unchecked")
    private static void preservesExpressionPrecedence() {
        ParseResponse response = parse("SELECT id FROM t WHERE NOT a=1 OR b=2 AND (c=3 OR d=4);");
        check(response.ok(), "precedence SQL should parse");
        Map<String, Object> where = (Map<String, Object>) response.statements().get(0).get("where");
        check(where.get("operator").equals("OR"), "OR must be AST root");
        Map<String, Object> left = (Map<String, Object>) where.get("left");
        check(left.get("kind").equals("UnaryExpr"), "NOT must bind before OR");
        Map<String, Object> right = (Map<String, Object>) where.get("right");
        check(right.get("operator").equals("AND"), "AND must bind before OR");
    }

    private static void parsesMultipleStatements() {
        ParseResponse response = parse("SELECT a FROM t;SELECT b FROM u;");
        check(response.ok() && response.statements().size() == 2, "multiple statements");
    }

    @SuppressWarnings("unchecked")
    private static void decodesStringLiteral() {
        ParseResponse response = parse("INSERT INTO books(title) VALUES ('Tom''s book');");
        List<Map<String, Object>> values = (List<Map<String, Object>>) response.statements().get(0).get("values");
        check(values.get(0).get("value").equals("Tom's book"), "escaped string value");
    }

    private static void reportsSyntaxLocationAndExpected() {
        ParseResponse response = parse("SELECT name student;");
        check(!response.ok(), "missing FROM should fail");
        check(response.error().stage().equals("PARSER"), "parser error stage");
        check(response.error().line() == 1 && response.error().column() == 13, "error location");
        check(response.error().expected().contains("FROM"), "expected must contain FROM");
    }

    private static void connectsLexerErrors() {
        ParseResponse response = parse("SELECT @ FROM t;");
        check(!response.ok(), "lexer failure should propagate");
        check(response.error().stage().equals("LEXER"), "lexer stage must be preserved");
        check(response.error().code().equals("LEXER_ILLEGAL_CHARACTER"), "lexer code must be preserved");
    }

    private static ParseResponse parse(String sql) {
        return new CompilerFrontEnd().parseSql(sql);
    }

    private static String kind(ParseResponse response, int index) {
        return (String) response.statements().get(index).get("kind");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
