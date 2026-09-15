package com.chainpage.sqlcompiler.parser;

import com.chainpage.sqlcompiler.lexer.Token;
import com.chainpage.sqlcompiler.lexer.TokenType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用预测分析表和显式栈实现的 LL(1) Parser。 */
public final class Parser {
    private static final String ID = "IDENTIFIER";
    private static final String INTEGER = "INT_LITERAL";
    private static final String STRING = "STRING_LITERAL";
    private static final String EOF = "EOF";
    private static final Map<String, Map<String, List<String>>> TABLE = buildTable();

    public ParseResponse parse(ParseRequest request) {
        ParserError invalid = validate(request);
        if (invalid != null) return ParseResponse.failure(invalid);
        try {
            return ParseResponse.success(buildProgram(analyze(request.tokens())));
        } catch (ParseFailure failure) {
            return ParseResponse.failure(failure.error);
        }
    }

    private Node analyze(List<Token> tokens) {
        Node root = new Node("PROGRAM");
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        int input = 0;
        while (!stack.isEmpty()) {
            Node top = stack.pop();
            Token lookahead = tokens.get(input);
            String terminal = terminalOf(lookahead);
            if (isTerminal(top.symbol)) {
                if (!top.symbol.equals(terminal)) throw unexpected(lookahead, List.of(top.symbol));
                top.token = lookahead;
                input++;
                continue;
            }
            List<String> production = TABLE.get(top.symbol).get(terminal);
            if (production == null) {
                throw unexpected(lookahead, new ArrayList<>(TABLE.get(top.symbol).keySet()));
            }
            for (String symbol : production) top.children.add(new Node(symbol));
            for (int i = top.children.size() - 1; i >= 0; i--) stack.push(top.children.get(i));
        }
        if (input != tokens.size()) throw unexpected(tokens.get(input), List.of(EOF));
        return root;
    }

    private List<Map<String, Object>> buildProgram(Node program) {
        List<Map<String, Object>> result = new ArrayList<>();
        Node cursor = program;
        while (cursor.children.size() == 2) {
            result.add(buildStatement(cursor.children.get(0)));
            cursor = cursor.children.get(1);
        }
        return result;
    }

    private Map<String, Object> buildStatement(Node statement) {
        Node value = statement.children.get(0);
        return switch (value.symbol) {
            case "CREATE_STMT" -> buildCreate(value);
            case "INSERT_STMT" -> buildInsert(value);
            case "SELECT_STMT" -> buildSelect(value);
            case "DELETE_STMT" -> buildDelete(value);
            default -> throw new IllegalStateException("未知语句：" + value.symbol);
        };
    }

    private Map<String, Object> buildCreate(Node node) {
        Map<String, Object> result = ast("CreateTableStmt", token(node, 0));
        result.put("table", token(node, 2).lexeme());
        List<Map<String, Object>> columns = new ArrayList<>();
        addColumn(node.children.get(4), columns);
        Node tail = node.children.get(5);
        while (!tail.children.isEmpty()) {
            addColumn(tail.children.get(1), columns);
            tail = tail.children.get(2);
        }
        result.put("columns", columns);
        return result;
    }

    private void addColumn(Node node, List<Map<String, Object>> columns) {
        Token name = token(node, 0);
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name.lexeme());
        column.put("dataType", token(node.children.get(1), 0).lexeme());
        column.put("loc", loc(name));
        columns.add(column);
    }

    private Map<String, Object> buildInsert(Node node) {
        Map<String, Object> result = ast("InsertStmt", token(node, 0));
        result.put("table", token(node, 2).lexeme());
        result.put("columns", buildIds(node.children.get(4)));
        result.put("values", buildValues(node.children.get(8)));
        return result;
    }

    private Map<String, Object> buildSelect(Node node) {
        Map<String, Object> result = ast("SelectStmt", token(node, 0));
        Node selection = node.children.get(1).children.get(0);
        result.put("columns", selection.symbol.equals("*") ? List.of("*") : buildIds(selection));
        result.put("table", token(node, 3).lexeme());
        Node where = node.children.get(4);
        result.put("where", where.children.isEmpty() ? null : buildExpression(where.children.get(1)));
        return result;
    }

    private Map<String, Object> buildDelete(Node node) {
        Map<String, Object> result = ast("DeleteStmt", token(node, 0));
        result.put("table", token(node, 2).lexeme());
        Node where = node.children.get(3);
        result.put("where", where.children.isEmpty() ? null : buildExpression(where.children.get(1)));
        return result;
    }

    private List<String> buildIds(Node node) {
        List<String> result = new ArrayList<>();
        result.add(token(node, 0).lexeme());
        Node tail = node.children.get(1);
        while (!tail.children.isEmpty()) {
            result.add(token(tail, 1).lexeme());
            tail = tail.children.get(2);
        }
        return result;
    }

    private List<Map<String, Object>> buildValues(Node node) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(buildLiteral(node.children.get(0)));
        Node tail = node.children.get(1);
        while (!tail.children.isEmpty()) {
            result.add(buildLiteral(tail.children.get(1)));
            tail = tail.children.get(2);
        }
        return result;
    }

    private Map<String, Object> buildExpression(Node expression) {
        Node or = expression.children.get(0);
        Map<String, Object> result = buildAnd(or.children.get(0));
        Node tail = or.children.get(1);
        while (!tail.children.isEmpty()) {
            result = binary(token(tail, 0), result, buildAnd(tail.children.get(1)));
            tail = tail.children.get(2);
        }
        return result;
    }

    private Map<String, Object> buildAnd(Node node) {
        Map<String, Object> result = buildNot(node.children.get(0));
        Node tail = node.children.get(1);
        while (!tail.children.isEmpty()) {
            result = binary(token(tail, 0), result, buildNot(tail.children.get(1)));
            tail = tail.children.get(2);
        }
        return result;
    }

    private Map<String, Object> buildNot(Node node) {
        if (node.children.get(0).symbol.equals("NOT")) {
            Token operator = token(node, 0);
            Map<String, Object> result = ast("UnaryExpr", operator);
            result.put("operator", "NOT");
            result.put("operand", buildNot(node.children.get(1)));
            return result;
        }
        return buildComparison(node.children.get(0));
    }

    private Map<String, Object> buildComparison(Node node) {
        Map<String, Object> left = buildPrimary(node.children.get(0));
        Node tail = node.children.get(1);
        if (tail.children.isEmpty()) return left;
        Token operator = token(tail.children.get(0), 0);
        return binary(operator, left, buildPrimary(tail.children.get(1)));
    }

    private Map<String, Object> buildPrimary(Node node) {
        Node first = node.children.get(0);
        if (first.symbol.equals(ID)) {
            Map<String, Object> result = ast("IdentifierExpr", first.token);
            result.put("name", first.token.lexeme());
            return result;
        }
        if (first.symbol.equals(INTEGER) || first.symbol.equals(STRING)) return buildLiteral(first);
        return buildExpression(node.children.get(1));
    }

    private Map<String, Object> buildLiteral(Node node) {
        Node terminal = node.symbol.equals("LITERAL") ? node.children.get(0) : node;
        Token token = terminal.token;
        Map<String, Object> result = ast("LiteralExpr", token);
        if (token.type() == TokenType.INT_LITERAL) {
            result.put("literalType", "INT");
            try {
                result.put("value", Integer.parseInt(token.lexeme()));
            } catch (NumberFormatException exception) {
                throw new ParseFailure(new ParserError("PARSER", "PARSER_INT_OUT_OF_RANGE",
                        "整数超出 Java int 范围", token.line(), token.column(), List.of(INTEGER)));
            }
        } else {
            result.put("literalType", "VARCHAR");
            result.put("value", token.lexeme().substring(1, token.lexeme().length() - 1).replace("''", "'"));
        }
        return result;
    }

    private Map<String, Object> binary(Token operator, Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> result = ast("BinaryExpr", operator);
        result.put("operator", operator.lexeme());
        result.put("left", left);
        result.put("right", right);
        return result;
    }

    private static Map<String, Object> ast(String kind, Token token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("loc", loc(token));
        return result;
    }

    private static Map<String, Object> loc(Token token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("line", token.line());
        result.put("column", token.column());
        return result;
    }

    private static Token token(Node node, int index) { return node.children.get(index).token; }
    private static boolean isTerminal(String symbol) { return !TABLE.containsKey(symbol); }

    private static String terminalOf(Token token) {
        return switch (token.type()) {
            case IDENTIFIER -> ID;
            case INT_LITERAL -> INTEGER;
            case STRING_LITERAL -> STRING;
            case EOF -> EOF;
            default -> token.lexeme();
        };
    }

    private static ParseFailure unexpected(Token token, List<String> expected) {
        return new ParseFailure(new ParserError("PARSER", "PARSER_UNEXPECTED_TOKEN",
                "Token 不符合 LL(1) 文法：" + terminalOf(token), token.line(), token.column(), expected));
    }

    private static ParserError validate(ParseRequest request) {
        if (request == null || request.tokens() == null)
            return invalid("请求必须包含 tokens 数组", null, null);
        List<Token> tokens = request.tokens();
        if (tokens.isEmpty()) return invalid("Token 数组必须以 EOF 结束", null, null);
        int eofCount = 0;
        for (Token token : tokens) {
            if (token == null || token.type() == null || token.lexeme() == null
                    || token.line() < 1 || token.column() < 1)
                return invalid("Token 字段不完整或位置不是正整数", null, null);
            if (token.type() == TokenType.EOF) eofCount++;
        }
        Token last = tokens.get(tokens.size() - 1);
        if (eofCount != 1 || last.type() != TokenType.EOF)
            return invalid("Token 数组必须以且只能以一个 EOF 结束", last.line(), last.column());
        return null;
    }

    private static ParserError invalid(String message, Integer line, Integer column) {
        return new ParserError("PARSER", "PARSER_INVALID_REQUEST", message, line, column, List.of(EOF));
    }

    private static Map<String, Map<String, List<String>>> buildTable() {
        Grammar g = new Grammar();
        g.add("PROGRAM", List.of("CREATE", "INSERT", "SELECT", "DELETE"), "STMT", "PROGRAM");
        g.add("PROGRAM", List.of(EOF), EOF);
        g.add("STMT", List.of("CREATE"), "CREATE_STMT");
        g.add("STMT", List.of("INSERT"), "INSERT_STMT");
        g.add("STMT", List.of("SELECT"), "SELECT_STMT");
        g.add("STMT", List.of("DELETE"), "DELETE_STMT");
        g.add("CREATE_STMT", List.of("CREATE"), "CREATE", "TABLE", ID, "(", "COLUMN_DEF", "COLUMN_TAIL", ")", ";");
        g.add("COLUMN_DEF", List.of(ID), ID, "DATA_TYPE");
        g.add("DATA_TYPE", List.of("INT"), "INT");
        g.add("DATA_TYPE", List.of("VARCHAR"), "VARCHAR");
        g.add("COLUMN_TAIL", List.of(","), ",", "COLUMN_DEF", "COLUMN_TAIL");
        g.add("COLUMN_TAIL", List.of(")"));
        g.add("INSERT_STMT", List.of("INSERT"), "INSERT", "INTO", ID, "(", "ID_LIST", ")", "VALUES", "(", "VALUE_LIST", ")", ";");
        g.add("SELECT_STMT", List.of("SELECT"), "SELECT", "SELECT_LIST", "FROM", ID, "WHERE_OPT", ";");
        g.add("DELETE_STMT", List.of("DELETE"), "DELETE", "FROM", ID, "WHERE_OPT", ";");
        g.add("SELECT_LIST", List.of("*"), "*");
        g.add("SELECT_LIST", List.of(ID), "ID_LIST");
        g.add("ID_LIST", List.of(ID), ID, "ID_TAIL");
        g.add("ID_TAIL", List.of(","), ",", ID, "ID_TAIL");
        g.add("ID_TAIL", List.of(")", "FROM"));
        g.add("VALUE_LIST", List.of(INTEGER, STRING), "LITERAL", "VALUE_TAIL");
        g.add("VALUE_TAIL", List.of(","), ",", "LITERAL", "VALUE_TAIL");
        g.add("VALUE_TAIL", List.of(")"));
        g.add("LITERAL", List.of(INTEGER), INTEGER);
        g.add("LITERAL", List.of(STRING), STRING);
        g.add("WHERE_OPT", List.of("WHERE"), "WHERE", "EXPR");
        g.add("WHERE_OPT", List.of(";"));
        g.add("EXPR", exprStarts(), "OR_EXPR");
        g.add("OR_EXPR", exprStarts(), "AND_EXPR", "OR_TAIL");
        g.add("OR_TAIL", List.of("OR"), "OR", "AND_EXPR", "OR_TAIL");
        g.add("OR_TAIL", List.of(")", ";"));
        g.add("AND_EXPR", exprStarts(), "NOT_EXPR", "AND_TAIL");
        g.add("AND_TAIL", List.of("AND"), "AND", "NOT_EXPR", "AND_TAIL");
        g.add("AND_TAIL", List.of("OR", ")", ";"));
        g.add("NOT_EXPR", List.of("NOT"), "NOT", "NOT_EXPR");
        g.add("NOT_EXPR", primaryStarts(), "COMPARISON");
        g.add("COMPARISON", primaryStarts(), "PRIMARY", "COMPARISON_TAIL");
        g.add("COMPARISON_TAIL", List.of("=", "!=", ">", ">=", "<", "<="), "COMP_OP", "PRIMARY");
        g.add("COMPARISON_TAIL", List.of("AND", "OR", ")", ";"));
        for (String op : List.of("=", "!=", ">", ">=", "<", "<=")) g.add("COMP_OP", List.of(op), op);
        g.add("PRIMARY", List.of(ID), ID);
        g.add("PRIMARY", List.of(INTEGER), INTEGER);
        g.add("PRIMARY", List.of(STRING), STRING);
        g.add("PRIMARY", List.of("("), "(", "EXPR", ")");
        return g.table;
    }

    private static List<String> exprStarts() { return List.of("NOT", ID, INTEGER, STRING, "("); }
    private static List<String> primaryStarts() { return List.of(ID, INTEGER, STRING, "("); }

    private static final class Grammar {
        private final Map<String, Map<String, List<String>>> table = new LinkedHashMap<>();
        private void add(String nonterminal, List<String> lookaheads, String... production) {
            Map<String, List<String>> row = table.computeIfAbsent(nonterminal, key -> new LinkedHashMap<>());
            for (String lookahead : lookaheads) {
                if (row.put(lookahead, List.of(production)) != null)
                    throw new IllegalStateException("LL(1) 表冲突：" + nonterminal + ", " + lookahead);
            }
        }
    }

    private static final class Node {
        private final String symbol;
        private final List<Node> children = new ArrayList<>();
        private Token token;
        private Node(String symbol) { this.symbol = symbol; }
    }

    private static final class ParseFailure extends RuntimeException {
        private final ParserError error;
        private ParseFailure(ParserError error) { super(error.message()); this.error = error; }
    }
}
