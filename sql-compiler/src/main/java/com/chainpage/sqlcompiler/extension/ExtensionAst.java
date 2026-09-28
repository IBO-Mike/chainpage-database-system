package com.chainpage.sqlcompiler.extension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/**
 * 拓展版 AST 校验入口——与基础 {@link com.chainpage.sqlcompiler.ast.AstService} 相互独立，
 * 对扩展方言的语句树做<b>递归结构验证</b>；每个新增节点（UpdateStmt/Join/GroupBy/OrderBy/
 * AggregateExpr/IsNullExpr 等）都有完整的 JSON 字段定义，缺失、多余类型不符都会被拒绝。
 *
 * <p>校验策略：先限定 statements 只能包含五种语句节点，再由 {@link #check} 按 kind 分派；
 * 表达式节点由 {@link #expression} 白名单校验后递归 check。异常通过 IllegalArgumentException
 * 与 Failure 两种方式抛出，最终由 ExtensionResponse.run 统一转为错误响应。</p>
 */
public final class ExtensionAst {
    /**
     * 校验入口：请求形如 {"statements": [...]}。
     * 逐条校验语句节点的 kind 合法性与结构，返回深拷贝后的 statements。
     */
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

    /**
     * 节点校验主分派：每个节点必须有正整数 loc；随后按 kind 校验各自字段，
     * 并递归校验所有子节点。不支持的 kind 抛 UNSUPPORTED_NODE。
     */
    private static void check(Map<String, Object> n) {
        Map<String, Object> loc = map(n.get("loc"));
        if (loc == null || !(loc.get("line") instanceof Number row) || row.intValue() < 1
                || !(loc.get("column") instanceof Number col) || col.intValue() < 1)
            throw fail("AST", "INVALID_NODE", "节点必须有正整数 loc", null);
        switch (text(n, "kind")) {
            case "CreateTableStmt" -> { text(n, "table"); children(n, "columns", "ColumnDef", false); }
            case "ColumnDef" -> { text(n, "name"); text(n, "dataType"); flag(n, "nullable"); }
            case "InsertStmt" -> {
                text(n, "table"); names(n.get("columns"));                  // 列名列表非空且均为字符串
                List<?> rows = (List<?>) n.get("rows"); if (rows.isEmpty()) throw new IllegalArgumentException();
                for (Object values : rows) {                                // 每行 VALUES 都是非空表达式列表
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
                // groupBy/orderBy 字段必须存在，允许为 null；非空时类型必须匹配
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

    /** 表达式节点白名单校验后递归 check。 */
    private static void expression(Map<String, Object> n) {
        if (!Set.of("NullLiteralExpr", "LiteralExpr", "IdentifierExpr", "StarExpr", "UnaryExpr", "BinaryExpr", "IsNullExpr", "AggregateExpr").contains(text(n, "kind")))
            throw fail("AST", "INVALID_NODE", "需要表达式节点", n);
        check(n);
    }

    /** 校验非空表达式数组字段。 */
    private static void expressions(Map<String, Object> n, String key) {
        List<Map<String, Object>> items = nodes(n.get(key)); if (items.isEmpty()) throw new IllegalArgumentException();
        for (Map<String, Object> item : items) expression(item);
    }

    /** 校验子节点数组：非 empty 时不得为空，每项 kind 必须匹配且递归合法。 */
    private static void children(Map<String, Object> n, String key, String kind, boolean empty) {
        List<Map<String, Object>> items = nodes(n.get(key)); if (!empty && items.isEmpty()) throw new IllegalArgumentException();
        for (Map<String, Object> item : items) { if (!text(item, "kind").equals(kind)) throw new IllegalArgumentException(); check(item); }
    }

    /** 校验单个子节点：kind 必须匹配且递归合法。 */
    private static void child(Map<String, Object> n, String key, String kind) {
        Map<String, Object> child = map(n.get(key)); if (!text(child, "kind").equals(kind)) throw new IllegalArgumentException(); check(child);
    }

    /** 可空表达式字段：字段必须存在，null 合法，否则递归校验。 */
    private static void optional(Map<String, Object> n, String key) { required(n, key); if (n.get(key) != null) expression(map(n.get(key))); }

    /** 字段必须存在的通用检查。 */
    private static void required(Map<String, Object> n, String key) { if (!n.containsKey(key)) throw new IllegalArgumentException(key); }

    /** 可空字符串字段：字段必须存在，null 合法，非 null 时必须是字符串。 */
    private static void nullableText(Map<String, Object> n, String key) { required(n, key); if (n.get(key) != null) text(n, key); }

    /** 布尔标志字段：必须存在且为 Boolean。 */
    private static void flag(Map<String, Object> n, String key) { if (!(n.get(key) instanceof Boolean)) throw new IllegalArgumentException(key); }

    /** 非空字符串数组字段校验（用于 InsertStmt.columns）。 */
    private static void names(Object value) {
        List<?> names = (List<?>) value; if (names.isEmpty()) throw new IllegalArgumentException();
        for (Object name : names) if (!(name instanceof String text) || text.isEmpty()) throw new IllegalArgumentException();
    }
}
