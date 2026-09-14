package com.chainpage.sqlcompiler.ast;

import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.parser.ParseResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AstServiceTest {
    private static int checks;

    public static void main(String[] args) {
        parserOutputRoundTripsThroughAstInterface();
        validatesEveryStatementKind();
        rejectsInvalidNodes();
        rejectsMalformedJson();
        validatesSelectStarRule();
        System.out.println("AST tests passed: " + checks);
    }

    private static void parserOutputRoundTripsThroughAstInterface() {
        ParseResponse parsed = new CompilerFrontEnd().parseSql(
                "SELECT name FROM student WHERE age >= 18 AND name != 'Tom''s';");
        check(parsed.ok(), "Parser should produce AST input");
        Map<String, Object> parserNode = parsed.statements().get(0);
        AstService service = new AstService();
        AstResponse made = service.makeNode(new MakeNodeRequest(parserNode));
        check(made.ok(), "Parser AST must pass make_node validation");
        check(made.json() != null && made.json().contains("SelectStmt"), "make_node returns JSON");
        AstResponse restored = service.parseNode(new ParseNodeRequest(made.json()));
        check(restored.ok(), "serialized AST should parse");
        check(restored.node().equals(made.node()), "AST JSON round trip must preserve node");
    }

    private static void validatesEveryStatementKind() {
        ParseResponse parsed = new CompilerFrontEnd().parseSql(
                "CREATE TABLE t(id INT,name VARCHAR);"
                        + "INSERT INTO t(id,name) VALUES(1,'A');"
                        + "SELECT * FROM t;DELETE FROM t WHERE id=1;");
        check(parsed.ok(), "all statement kinds parse");
        AstService service = new AstService();
        for (Map<String, Object> statement : parsed.statements()) {
            check(service.makeNode(new MakeNodeRequest(statement)).ok(), "statement validates: " + statement.get("kind"));
        }
    }

    private static void rejectsInvalidNodes() {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("kind", "IdentifierExpr");
        invalid.put("loc", loc(1, 1));
        AstResponse response = new AstService().makeNode(new MakeNodeRequest(invalid));
        check(!response.ok(), "missing name should fail");
        check(response.error().stage().equals("AST"), "error stage");
        check(response.error().code().equals("AST_INVALID_NODE"), "error code");
        check(response.error().line() == 1 && response.error().column() == 1, "node error location");
    }

    private static void rejectsMalformedJson() {
        AstResponse response = new AstService().parseNode(new ParseNodeRequest("{not-json}"));
        check(!response.ok(), "malformed JSON should fail");
        check(response.error().code().equals("AST_INVALID_NODE"), "JSON error uses AST code");
    }

    private static void validatesSelectStarRule() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", "SelectStmt");
        node.put("loc", loc(1, 1));
        node.put("columns", new ArrayList<>(List.of("*", "id")));
        node.put("table", "t");
        node.put("where", null);
        check(!new AstService().makeNode(new MakeNodeRequest(node)).ok(), "star must appear alone");
    }

    private static Map<String, Object> loc(int line, int column) {
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("line", line);
        loc.put("column", column);
        return loc;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
