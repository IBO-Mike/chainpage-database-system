package com.chainpage.sqlcompiler.recovery;

import com.chainpage.sqlcompiler.ast.AstService;
import com.chainpage.sqlcompiler.ast.JsonCodec;
import com.chainpage.sqlcompiler.ast.MakeNodeRequest;
import com.chainpage.sqlcompiler.lexer.LexRequest;
import com.chainpage.sqlcompiler.lexer.LexResponse;
import com.chainpage.sqlcompiler.lexer.Lexer;
import com.chainpage.sqlcompiler.lexer.Token;
import com.chainpage.sqlcompiler.lexer.TokenType;
import com.chainpage.sqlcompiler.parser.ParseRequest;
import com.chainpage.sqlcompiler.parser.ParseResponse;
import com.chainpage.sqlcompiler.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecoveringParserTest {
    private static int checks;

    public static void main(String[] args) {
        matchesStrictParserOnValidPrograms();
        recoversMixedStatementsInOrder();
        reportsExactLocationsAndUnexpectedTokens();
        synchronizesOnlyAtSemicolonOrEof();
        respectsStringAndCommentBoundaries();
        discardsInvalidAndPartialAst();
        rejectsMalformedRequests();
        preservesInputAndSeparatesCalls();
        handlesLongInput();
        System.out.println("Recovering Parser tests passed: " + checks);
    }

    private static void matchesStrictParserOnValidPrograms() {
        for (String sql : List.of("", "  \n-- comment ;\n/* ; */",
                "CREATE TABLE student(id INT,name VARCHAR);\n"
                        + "INSERT INTO student(id,name) VALUES(1,'Tom''s book');\n"
                        + "SELECT name,id FROM student WHERE NOT id=1 OR id>2 AND (name='Tom' OR id=3);\n"
                        + "DELETE FROM student WHERE id=1;",
                "SELECT * FROM t;\nSELECT id FROM u;\n")) {
            List<Token> tokens = lex(sql);
            ParseResponse strict = new Parser().parse(new ParseRequest(tokens));
            RecoverParseResponse response = new RecoveringParser().parse(new RecoverParseRequest(tokens));
            check(strict.ok() && response.ok(), "valid program accepted");
            check(response.errors().isEmpty(), "valid program has no recovered errors");
            check(response.statements().equals(strict.statements()), "AST and all locations match original Parser");
            for (Map<String, Object> statement : response.statements())
                check(new AstService().makeNode(new MakeNodeRequest(statement)).ok(), "complete valid AST");
        }
    }

    private static void recoversMixedStatementsInOrder() {
        String sql = "CREATE TABLE t(id INT);\n"
                + "SELECT id t;\n"
                + "INSERT INTO t(id) VALUES(1);\n"
                + "DELETE t;\n"
                + "SELECT * FROM t;";
        RecoverParseResponse response = parse(sql);
        check(response.statements().stream().map(node -> node.get("kind")).toList()
                .equals(List.of("CreateTableStmt", "InsertStmt", "SelectStmt")), "valid statements kept in source order");
        check(response.errors().size() == 2, "one error for each bad statement");
        check(response.errors().stream().map(RecoveryError::line).toList().equals(List.of(2, 4)), "errors in source order");
        check(!new Parser().parse(new ParseRequest(lex(sql))).ok(), "strict Parser keeps fail-fast behavior");
        for (Map<String, Object> statement : response.statements())
            check(new AstService().makeNode(new MakeNodeRequest(statement)).ok(), "no partial AST in result");

        Map<String, Object> envelope = response.toMap();
        check(envelope.get("ok").equals(true) && !envelope.containsKey("error"), "recoverable errors still return ok true");
        Map<String, Object> data = map(envelope.get("data"));
        check(data.keySet().equals(Set.of("statements", "errors")), "exact success data fields");
        check(((List<?>) data.get("errors")).size() == 2, "errors serialized into data");
        check(JsonCodec.parse(JsonCodec.stringify(envelope)) instanceof Map<?, ?>, "JSON compatible response");
    }

    private static void reportsExactLocationsAndUnexpectedTokens() {
        String sql = "SELECT id FROM t;\nSELECT name student;\nSELECT * FROM u";
        RecoverParseResponse response = parse(sql);
        check(response.statements().size() == 1 && response.errors().size() == 2, "complete prefix kept, bad tail discarded");
        RecoveryError first = response.errors().get(0);
        check(first.stage().equals("PARSER") && first.code().equals("PARSER_UNEXPECTED_TOKEN"), "original stage and code");
        check(first.line() == 2 && first.column() == 13, "exact error source position");
        check(first.unexpected().equals("student") && first.expected().contains("FROM"), "unexpected lexeme and expected terminal");
        RecoveryError last = response.errors().get(1);
        Token eof = lex(sql).get(lex(sql).size() - 1);
        check(last.line() == eof.line() && last.column() == eof.column(), "EOF uses original source position");
        check(last.unexpected().equals("EOF") && last.expected().contains(";"), "missing semicolon at EOF");
        for (RecoveryError error : response.errors()) {
            check(error.toMap().keySet().equals(Set.of("stage", "code", "message", "line", "column", "unexpected", "expected")),
                    "complete error fields");
            check(!error.expected().isEmpty(), "expected alternatives retained");
        }
        RecoverParseResponse delimiter = parse("SELECT id FROM ; SELECT id FROM u;");
        check(delimiter.errors().get(0).unexpected().equals(";"), "semicolon can be the unexpected token");
        check(delimiter.errors().get(0).expected().contains("IDENTIFIER"), "semicolon error expects table name");
        check(delimiter.statements().size() == 1, "continues after error on synchronization token");
    }

    private static void synchronizesOnlyAtSemicolonOrEof() {
        RecoverParseResponse response = parse("SELECT id FROM t\nDELETE FROM t;\nSELECT id FROM u;");
        check(response.errors().size() == 1 && response.errors().get(0).unexpected().equals("DELETE"),
                "missing separator reports next keyword");
        check(response.statements().size() == 1 && response.statements().get(0).get("table").equals("u"),
                "skip through next semicolon; do not guess missing separators");
        response = parse("SELECT id FROM t WHERE (id=1;\nSELECT id FROM u;");
        check(response.errors().size() == 1 && response.statements().size() == 1, "unclosed parentheses recover at semicolon");
        check(response.errors().get(0).unexpected().equals(";"), "unclosed expression reports boundary");
        response = parse(";; SELECT * FROM t; ;");
        check(response.errors().size() == 3 && response.statements().size() == 1, "empty statements are errors under current grammar");
        response = parse("SELECT FROM t; DELETE t; CREATE t;");
        check(response.statements().isEmpty() && response.errors().size() == 3, "consecutive errors all collected");
        response = parse("SELECT");
        check(response.statements().isEmpty() && response.errors().size() == 1, "incomplete EOF statement reported once");
        check(response.errors().get(0).unexpected().equals("EOF"), "incomplete EOF diagnostic");
        response = parse("SELECT FROM t;\n");
        check(response.errors().size() == 1, "trailing EOF adds no phantom statement or duplicate error");
    }

    private static void respectsStringAndCommentBoundaries() {
        String sql = "SELECT name t WHERE name=';';\n"
                + "-- comment ; SELECT broken\n"
                + "INSERT INTO t(name) VALUES('Tom''s;book'); /* ; ; */\n"
                + "SELECT name FROM t WHERE name='a;\nb';";
        RecoverParseResponse response = parse(sql);
        check(response.errors().size() == 1 && response.statements().size() == 2, "only delimiter Tokens split statements");
        Map<String, Object> value = map(((List<?>) response.statements().get(0).get("values")).get(0));
        check(value.get("value").equals("Tom's;book"), "quoted semicolon and escaped quote decoded normally");
        Map<String, Object> right = map(map(response.statements().get(1).get("where")).get("right"));
        check(right.get("value").equals("a;\nb"), "multiline string intact");
        check(map(response.statements().get(1).get("loc")).get("line").equals(4), "comments do not reset locations");
    }

    private static void discardsInvalidAndPartialAst() {
        RecoverParseResponse response = parse("CREATE TABLE bad(id INT,);"
                + "INSERT INTO t(id) VALUES(1,);"
                + "SELECT id FROM t WHERE id=;"
                + "DELETE FROM t WHERE;"
                + "INSERT INTO t(id) VALUES(2147483648);"
                + "SELECT * FROM survivor;");
        check(response.errors().size() == 5 && response.statements().size() == 1, "all invalid statement kinds discarded");
        check(response.statements().get(0).get("table").equals("survivor"), "valid suffix survives all failures");
        RecoveryError overflow = response.errors().get(4);
        check(overflow.code().equals("PARSER_INT_OUT_OF_RANGE") && overflow.unexpected().equals("2147483648"),
                "AST construction failure also recoverable");
        check(overflow.expected().equals(List.of("INT_LITERAL")), "AST construction error details preserved");
    }

    private static void rejectsMalformedRequests() {
        RecoveringParser parser = new RecoveringParser();
        invalid(parser.parse(null));
        invalid(parser.parse(new RecoverParseRequest(null)));
        invalid(parser.parse(new RecoverParseRequest(List.of())));
        List<Token> noEof = new ArrayList<>(lex("SELECT * FROM t;"));
        noEof.remove(noEof.size() - 1);
        invalid(parser.parse(new RecoverParseRequest(noEof)));
        Token eof = new Token(TokenType.EOF, "", 1, 1);
        invalid(parser.parse(new RecoverParseRequest(List.of(eof, eof))));
        invalid(parser.parse(new RecoverParseRequest(List.of(eof, new Token(TokenType.KEYWORD, "SELECT", 1, 2)))));
        List<Token> nullToken = new ArrayList<>();
        nullToken.add(null);
        nullToken.add(eof);
        invalid(parser.parse(new RecoverParseRequest(nullToken)));
        for (Token token : List.of(new Token(null, "SELECT", 1, 1), new Token(TokenType.KEYWORD, null, 1, 1),
                new Token(TokenType.KEYWORD, "SELECT", 0, 1), new Token(TokenType.KEYWORD, "SELECT", 1, -1)))
            invalid(parser.parse(new RecoverParseRequest(List.of(token, eof))));
        for (String value : List.of("", "'", "missingQuotes", "'unclosed", "'bad'quote'")) {
            List<Token> tokens = new ArrayList<>(lex("INSERT INTO t(name) VALUES('ok'); SELECT * FROM t;"));
            int index = 0;
            while (tokens.get(index).type() != TokenType.STRING_LITERAL) index++;
            Token original = tokens.get(index);
            tokens.set(index, new Token(TokenType.STRING_LITERAL, value, original.line(), original.column()));
            invalid(parser.parse(new RecoverParseRequest(tokens)));
        }
        for (String sql : List.of("INSERT INTO t(name) VALUES('');", "INSERT INTO t(name) VALUES('''');"))
            check(parse(sql).errors().isEmpty(), "empty and escaped-quote literals remain valid");
    }

    private static void preservesInputAndSeparatesCalls() {
        List<Token> tokens = List.copyOf(lex("SELECT name t; SELECT * FROM u;"));
        String before = JsonCodec.stringify(tokens.stream().map(Token::toMap).toList());
        RecoveringParser parser = new RecoveringParser();
        RecoverParseResponse first = parser.parse(new RecoverParseRequest(tokens));
        check(first.ok() && first.errors().size() == 1 && first.statements().size() == 1, "immutable input accepted");
        check(JsonCodec.stringify(tokens.stream().map(Token::toMap).toList()).equals(before), "no token insertion, removal or location rewrite");
        RecoverParseResponse again = parser.parse(new RecoverParseRequest(tokens));
        check(again.toMap().equals(first.toMap()), "deterministic recovery");
        first.statements().get(0).put("table", "changed");
        check(again.statements().get(0).get("table").equals("u"), "independent AST objects across calls");
        RecoverParseResponse clean = parser.parse(new RecoverParseRequest(lex("SELECT * FROM clean;")));
        check(clean.ok() && clean.errors().isEmpty() && clean.statements().size() == 1, "errors and ASTs do not leak across calls");
    }

    private static void handlesLongInput() {
        RecoverParseResponse response = parse("SELECT FROM t; SELECT * FROM t;\n".repeat(500));
        check(response.errors().size() == 500 && response.statements().size() == 500, "large mixed stream completes without retry loops");
        check(response.errors().get(499).line() == 500, "large stream keeps source positions");
        String content = "x;".repeat(10_000);
        response = parse("INSERT INTO t(name) VALUES('" + content + "'); SELECT FROM t; SELECT * FROM u;");
        check(response.statements().size() == 2 && response.errors().size() == 1, "long literal does not split or overflow validation stack");
        check(map(((List<?>) response.statements().get(0).get("values")).get(0)).get("value").equals(content), "long literal preserved");
    }

    private static void invalid(RecoverParseResponse response) {
        check(!response.ok() && response.error().code().equals("PARSER_INVALID_REQUEST"), "invalid stream is a request failure");
        check(response.statements().isEmpty() && response.errors().isEmpty(), "invalid stream never returns partial results");
        Map<String, Object> envelope = response.toMap();
        check(envelope.get("ok").equals(false) && !envelope.containsKey("data"), "uniform request failure envelope");
        Map<String, Object> error = map(envelope.get("error"));
        check(error.get("stage").equals("PARSER") && error.containsKey("unexpected")
                && error.containsKey("line") && error.containsKey("column") && !((List<?>) error.get("expected")).isEmpty(),
                "complete request error fields");
        check(JsonCodec.parse(JsonCodec.stringify(envelope)) instanceof Map<?, ?>, "request error is JSON compatible");
    }

    private static RecoverParseResponse parse(String sql) {
        RecoverParseResponse response = new RecoveringParser().parse(new RecoverParseRequest(lex(sql)));
        check(response.ok(), "recovery call completes");
        return response;
    }

    private static List<Token> lex(String sql) {
        LexResponse response = new Lexer().lex(new LexRequest(sql));
        check(response.ok(), "test SQL must tokenize");
        return response.tokens();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
