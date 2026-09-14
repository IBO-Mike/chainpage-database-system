package com.chainpage.sqlcompiler.extension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 扩展 AST 的独立结构验证入口；新增节点均有完整 JSON 字段定义。 */
public final class ExtensionAst {
    public ExtensionResponse validate(Map<String, Object> request) {
        return ExtensionResponse.run("AST", () -> {
            List<Map<String, Object>> statements = nodes(copy(request.get("statements")));
            for (Map<String, Object> statement : statements) {
                if (!Set.of("CreateTableStmt", "InsertStmt", "SelectStmt", "UpdateStmt", "DeleteStmt").contains(text(statement, "kind")))
                    throw fail("AST", "INVALID_NODE", "statements 只能包含语句", statement);
                check(statement);
            }
            return node("statements", statements);
        });
    }
    private static void check(Map<String, Object> n) {
        Map<String, Object> loc = map(n.get("loc"));
        if (loc == null || !(loc.get("line") instanceof Number row) || row.intValue() < 1
                || !(loc.get("column") instanceof Number col) || col.intValue() < 1)
            throw fail("AST", "INVALID_NODE", "节点必须有正整数 loc", null);
        switch (text(n, "kind")) {
            case "CreateTableStmt" -> { text(n, "table"); children(n, "columns", "ColumnDef", false); }
            case "ColumnDef" -> { text(n, "name"); text(n, "dataType"); flag(n, "nullable"); }
            case "InsertStmt" -> {
                text(n, "table"); names(n.get("columns"));
                List<?> rows = (List<?>) n.get("rows"); if (rows.isEmpty()) throw new IllegalArgumentException();
                for (Object values : rows) {
                    List<Map<String, Object>> expressions = nodes(values); if (expressions.isEmpty()) throw new IllegalArgumentException();
                    for (Map<String, Object> expr : expressions) expression(expr);
                }
            }
            case "UpdateStmt" -> { text(n, "table"); children(n, "assignments", "Assignment", false); optional(n, "where"); }
            case "Assignment" -> { text(n, "column"); expression(map(n.get("value"))); }
            case "DeleteStmt" -> { text(n, "table"); optional(n, "where"); }
            case "SelectStmt" -> {
                children(n, "items", "SelectItem", false); child(n, "from", "TableRef"); children(n, "joins", "Join", true);
                optional(n, "where"); optional(n, "having");
                for (String key : List.of("groupBy", "orderBy")) {
                    required(n, key); if (n.get(key) != null) child(n, key, key.equals("groupBy") ? "GroupBy" : "OrderBy");
                }
            }
            case "TableRef" -> { text(n, "table"); text(n, "alias"); }
            case "SelectItem" -> { expression(map(n.get("expression"))); nullableText(n, "alias"); }
            case "Join" -> {
                if (!Set.of("INNER", "LEFT").contains(text(n, "joinType"))) throw fail("AST", "UNSUPPORTED_NODE", "仅支持 INNER/LEFT JOIN", n);
                child(n, "table", "TableRef"); expression(map(n.get("on")));
            }
            case "GroupBy" -> expressions(n, "expressions");
            case "OrderBy" -> children(n, "items", "SortItem", false);
            case "SortItem" -> {
                expression(map(n.get("expression")));
                if (!Set.of("ASC", "DESC").contains(text(n, "direction")) || !Set.of("FIRST", "LAST").contains(text(n, "nulls")))
                    throw fail("AST", "INVALID_NODE", "排序方向或 NULLS 规则无效", n);
            }
            case "NullLiteralExpr" -> { required(n, "value"); if (n.get("value") != null) throw new IllegalArgumentException(); }
            case "LiteralExpr" -> { text(n, "literalType"); required(n, "value"); if (n.get("value") == null) throw new IllegalArgumentException(); }
            case "IdentifierExpr" -> { text(n, "name"); nullableText(n, "qualifier"); }
            case "StarExpr" -> nullableText(n, "qualifier");
            case "UnaryExpr" -> { text(n, "operator"); expression(map(n.get("operand"))); }
            case "BinaryExpr" -> { text(n, "operator"); expression(map(n.get("left"))); expression(map(n.get("right"))); }
            case "IsNullExpr" -> { flag(n, "negated"); expression(map(n.get("operand"))); }
            case "AggregateExpr" -> { text(n, "function"); expression(map(n.get("argument"))); }
            default -> throw fail("AST", "UNSUPPORTED_NODE", "不支持的 AST 节点：" + text(n, "kind"), n);
        }
    }
    private static void expression(Map<String, Object> n) {
        if (!Set.of("NullLiteralExpr", "LiteralExpr", "IdentifierExpr", "StarExpr", "UnaryExpr", "BinaryExpr", "IsNullExpr", "AggregateExpr").contains(text(n, "kind")))
            throw fail("AST", "INVALID_NODE", "需要表达式节点", n);
        check(n);
    }
    private static void expressions(Map<String, Object> n, String key) {
        List<Map<String, Object>> items = nodes(n.get(key)); if (items.isEmpty()) throw new IllegalArgumentException();
        for (Map<String, Object> item : items) expression(item);
    }
    private static void children(Map<String, Object> n, String key, String kind, boolean empty) {
        List<Map<String, Object>> items = nodes(n.get(key)); if (!empty && items.isEmpty()) throw new IllegalArgumentException();
        for (Map<String, Object> item : items) { if (!text(item, "kind").equals(kind)) throw new IllegalArgumentException(); check(item); }
    }
    private static void child(Map<String, Object> n, String key, String kind) {
        Map<String, Object> child = map(n.get(key)); if (!text(child, "kind").equals(kind)) throw new IllegalArgumentException(); check(child);
    }
    private static void optional(Map<String, Object> n, String key) { required(n, key); if (n.get(key) != null) expression(map(n.get(key))); }
    private static void required(Map<String, Object> n, String key) { if (!n.containsKey(key)) throw new IllegalArgumentException(key); }
    private static void nullableText(Map<String, Object> n, String key) { required(n, key); if (n.get(key) != null) text(n, key); }
    private static void flag(Map<String, Object> n, String key) { if (!(n.get(key) instanceof Boolean)) throw new IllegalArgumentException(key); }
    private static void names(Object value) {
        List<?> names = (List<?>) value; if (names.isEmpty()) throw new IllegalArgumentException();
        for (Object name : names) if (!(name instanceof String text) || text.isEmpty()) throw new IllegalArgumentException();
    }
}
