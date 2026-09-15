package com.chainpage.sqlcompiler.extension;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 扩展递归下降文法；输入仍为 {tokens: Token[]}，输出 {statements: Statement[]}。 */
public final class ExtensionParser {
    public ExtensionResponse parse(Map<String, Object> request) {
        return ExtensionResponse.run("PARSER", () -> node("statements", new Reader(nodes(request.get("tokens"))).program()));
    }
    private static final class Reader {
        final List<Map<String, Object>> tokens;
        int index;
        Reader(List<Map<String, Object>> tokens) {
            if (tokens == null || tokens.isEmpty()) throw fail("PARSER", "INVALID_REQUEST", "tokens 必须以唯一 EOF 结束", null);
            for (int i = 0; i < tokens.size(); i++) {
                Map<String, Object> t = tokens.get(i);
                String type = text(t, "type");
                if (!(t.get("lexeme") instanceof String) || !(t.get("line") instanceof Number row)
                        || !(t.get("column") instanceof Number col) || row.intValue() < 1 || col.intValue() < 1
                        || !Set.of("KEYWORD", "IDENTIFIER", "INT_LITERAL", "DECIMAL_LITERAL", "STRING_LITERAL", "OPERATOR", "DELIMITER", "EOF").contains(type)
                        || type.equals("EOF") != (i == tokens.size() - 1))
                    throw fail("PARSER", "INVALID_REQUEST", "Token 格式无效或 EOF 不在末尾", null);
            }
            this.tokens = tokens;
        }
        Map<String, Object> current() { return tokens.get(index); }
        String type() { return text(current(), "type"); }
        String word() { return type().equals("EOF") ? "EOF" : (String) current().get("lexeme"); }
        boolean at(String word) { return word().equals(word); }
        Map<String, Object> take() { return tokens.get(index++); }
        boolean accept(String word) { if (!at(word)) return false; take(); return true; }
        Map<String, Object> require(String word) { if (!at(word)) throw unexpected(word); return take(); }
        String identifier() {
            if (!type().equals("IDENTIFIER")) throw unexpected("IDENTIFIER");
            return ((String) take().get("lexeme")).toLowerCase(Locale.ROOT);
        }
        Map<String, Object> ast(String kind, Map<String, Object> token, Object... fields) {
            Map<String, Object> result = node("kind", kind, "loc", node("line", token.get("line"), "column", token.get("column")));
            result.putAll(node(fields)); return result;
        }
        Failure unexpected(String... expected) {
            Failure failure = fail("PARSER", "UNEXPECTED_TOKEN", "不支持的语法或意外 Token：" + word(), ast("", current()));
            failure.error.put("unexpected", word()); failure.error.put("expected", List.of(expected)); return failure;
        }
        List<Map<String, Object>> program() {
            List<Map<String, Object>> statements = new ArrayList<>();
            while (!at("EOF")) { statements.add(statement()); require(";"); }
            return statements;
        }
        Map<String, Object> statement() {
            Map<String, Object> start = current();
            if (accept("SELECT")) return select(start);
            if (accept("CREATE")) {
                require("TABLE"); String table = identifier(); require("(");
                List<Map<String, Object>> columns = new ArrayList<>();
                do {
                    Map<String, Object> at = current(); String name = identifier();
                    if (!Set.of("INT", "BIGINT", "DECIMAL", "VARCHAR", "BOOL", "BOOLEAN", "DATE").contains(word()))
                        throw unexpected("INT", "BIGINT", "DECIMAL", "VARCHAR", "BOOL", "DATE");
                    String dataType = (String) take().get("lexeme");
                    if (dataType.equals("BOOLEAN")) dataType = "BOOL";
                    boolean nullable = true;
                    if (accept("NOT")) { require("NULL"); nullable = false; } else accept("NULL");
                    columns.add(ast("ColumnDef", at, "name", name, "dataType", dataType, "nullable", nullable));
                } while (accept(","));
                require(")"); return ast("CreateTableStmt", start, "table", table, "columns", columns);
            }
            if (accept("INSERT")) {
                require("INTO"); String table = identifier(); require("(");
                List<String> columns = new ArrayList<>(); do { columns.add(identifier()); } while (accept(","));
                require(")"); require("VALUES"); List<List<Map<String, Object>>> rows = new ArrayList<>();
                do { require("("); rows.add(expressions()); require(")"); } while (accept(","));
                return ast("InsertStmt", start, "table", table, "columns", columns, "rows", rows);
            }
            if (accept("UPDATE")) {
                String table = identifier(); require("SET"); List<Map<String, Object>> assignments = new ArrayList<>();
                do {
                    Map<String, Object> at = current(); String column = identifier(); require("=");
                    assignments.add(ast("Assignment", at, "column", column, "value", expression()));
                } while (accept(","));
                return ast("UpdateStmt", start, "table", table, "assignments", assignments, "where", accept("WHERE") ? expression() : null);
            }
            if (accept("DELETE")) {
                require("FROM"); String table = identifier();
                return ast("DeleteStmt", start, "table", table, "where", accept("WHERE") ? expression() : null);
            }
            throw unexpected("CREATE", "INSERT", "SELECT", "UPDATE", "DELETE");
        }
        Map<String, Object> table() {
            Map<String, Object> start = current(); String name = identifier(), alias = name;
            if (accept("AS") || type().equals("IDENTIFIER")) alias = identifier();
            return ast("TableRef", start, "table", name, "alias", alias);
        }
        Map<String, Object> select(Map<String, Object> start) {
            List<Map<String, Object>> items = new ArrayList<>();
            do {
                Map<String, Object> at = current(), expr = expression();
                String alias = accept("AS") ? identifier() : null;
                items.add(ast("SelectItem", at, "expression", expr, "alias", alias));
            } while (accept(","));
            require("FROM"); Map<String, Object> from = table(); List<Map<String, Object>> joins = new ArrayList<>();
            while (at("JOIN") || at("INNER") || at("LEFT")) {
                Map<String, Object> at = current(); String joinType = "INNER";
                if (accept("LEFT")) { joinType = "LEFT"; accept("OUTER"); } else accept("INNER");
                require("JOIN"); Map<String, Object> right = table(); require("ON");
                joins.add(ast("Join", at, "joinType", joinType, "table", right, "on", expression()));
            }
            Map<String, Object> where = accept("WHERE") ? expression() : null, group = null, order = null;
            if (at("GROUP")) { Map<String, Object> at = take(); require("BY"); group = ast("GroupBy", at, "expressions", expressions()); }
            Map<String, Object> having = accept("HAVING") ? expression() : null;
            if (at("ORDER")) {
                Map<String, Object> at = take(); require("BY"); List<Map<String, Object>> sorts = new ArrayList<>();
                do {
                    Map<String, Object> sortAt = current(), expr = expression(); String direction = "ASC", nulls = "LAST";
                    if (accept("DESC")) direction = "DESC"; else accept("ASC");
                    nulls = direction.equals("ASC") ? "LAST" : "FIRST";
                    if (accept("NULLS")) { if (accept("FIRST")) nulls = "FIRST"; else require("LAST"); }
                    sorts.add(ast("SortItem", sortAt, "expression", expr, "direction", direction, "nulls", nulls));
                } while (accept(","));
                order = ast("OrderBy", at, "items", sorts);
            }
            return ast("SelectStmt", start, "items", items, "from", from, "joins", joins,
                    "where", where, "groupBy", group, "having", having, "orderBy", order);
        }
        List<Map<String, Object>> expressions() {
            List<Map<String, Object>> result = new ArrayList<>(); do { result.add(expression()); } while (accept(",")); return result;
        }
        Map<String, Object> expression() { return or(); }
        Map<String, Object> or() {
            Map<String, Object> left = and(); while (at("OR")) { Map<String, Object> op = take(); left = binary(op, left, and()); } return left;
        }
        Map<String, Object> and() {
            Map<String, Object> left = not(); while (at("AND")) { Map<String, Object> op = take(); left = binary(op, left, not()); } return left;
        }
        Map<String, Object> not() {
            if (at("NOT")) { Map<String, Object> op = take(); return ast("UnaryExpr", op, "operator", "NOT", "operand", not()); }
            return comparison();
        }
        Map<String, Object> comparison() {
            Map<String, Object> left = addition();
            if (at("IS")) { Map<String, Object> op = take(); boolean negated = accept("NOT"); require("NULL"); return ast("IsNullExpr", op, "operand", left, "negated", negated); }
            if (Set.of("=", "!=", "<>", ">", ">=", "<", "<=").contains(word())) { Map<String, Object> op = take(); return binary(op, left, addition()); }
            return left;
        }
        Map<String, Object> addition() {
            Map<String, Object> left = product(); while (at("+") || at("-")) { Map<String, Object> op = take(); left = binary(op, left, product()); } return left;
        }
        Map<String, Object> product() {
            Map<String, Object> left = unary(); while (at("*") || at("/")) { Map<String, Object> op = take(); left = binary(op, left, unary()); } return left;
        }
        Map<String, Object> unary() {
            if (at("-") || at("+")) {
                Map<String, Object> op = take(), operand = unary();
                if (operand.get("kind").equals("LiteralExpr") && operand.get("value") instanceof Number number) {
                    Object value = op.get("lexeme").equals("-") ? (number instanceof BigInteger b ? b.negate() : new BigDecimal(number.toString()).negate()) : number;
                    String numericType = value instanceof BigInteger b ? (b.bitLength() < 32 ? "INT" : "BIGINT") : "DECIMAL";
                    return ast("LiteralExpr", op, "literalType", numericType, "value", value);
                }
                return ast("UnaryExpr", op, "operator", op.get("lexeme"), "operand", operand);
            }
            return primary();
        }
        Map<String, Object> primary() {
            Map<String, Object> start = current();
            if (accept("(")) { Map<String, Object> expr = expression(); require(")"); return expr; }
            if (accept("NULL")) return ast("NullLiteralExpr", start, "value", null);
            if (at("TRUE") || at("FALSE")) return ast("LiteralExpr", start, "literalType", "BOOL", "value", take().get("lexeme").equals("TRUE"));
            if (accept("DATE")) {
                if (!type().equals("STRING_LITERAL")) throw unexpected("STRING_LITERAL");
                return ast("LiteralExpr", start, "literalType", "DATE", "value", string());
            }
            if (type().equals("STRING_LITERAL")) return ast("LiteralExpr", start, "literalType", "VARCHAR", "value", string());
            if (type().equals("INT_LITERAL") || type().equals("DECIMAL_LITERAL")) {
                String type = type(), lexeme = (String) take().get("lexeme");
                if (type.equals("DECIMAL_LITERAL")) return ast("LiteralExpr", start, "literalType", "DECIMAL", "value", new BigDecimal(lexeme));
                BigInteger number = new BigInteger(lexeme);
                return ast("LiteralExpr", start, "literalType", number.bitLength() < 32 ? "INT" : "BIGINT", "value", number);
            }
            if (Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(word())) {
                String function = (String) take().get("lexeme"); require("("); Map<String, Object> argument = expression(); require(")");
                return ast("AggregateExpr", start, "function", function, "argument", argument);
            }
            if (accept("*")) return ast("StarExpr", start, "qualifier", null);
            if (type().equals("IDENTIFIER")) {
                String name = identifier(), qualifier = null;
                if (accept(".")) {
                    qualifier = name;
                    if (accept("*")) return ast("StarExpr", start, "qualifier", qualifier);
                    name = identifier();
                }
                return ast("IdentifierExpr", start, "name", name, "qualifier", qualifier);
            }
            throw unexpected("IDENTIFIER", "INT_LITERAL", "DECIMAL_LITERAL", "STRING_LITERAL", "NULL", "(");
        }
        String string() {
            String value = (String) take().get("lexeme");
            if (value.length() < 2 || !value.startsWith("'") || !value.endsWith("'")) throw new IllegalArgumentException("quoted literal");
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        Map<String, Object> binary(Map<String, Object> op, Map<String, Object> left, Map<String, Object> right) {
            return ast("BinaryExpr", op, "operator", op.get("lexeme").equals("<>") ? "!=" : op.get("lexeme"), "left", left, "right", right);
        }
    }
}
