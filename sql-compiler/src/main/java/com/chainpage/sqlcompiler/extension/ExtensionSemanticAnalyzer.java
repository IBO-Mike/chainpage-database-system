package com.chainpage.sqlcompiler.extension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

public final class ExtensionSemanticAnalyzer {
    public ExtensionResponse analyze(Map<String, Object> request) {
        return ExtensionResponse.run("SEMANTIC", () -> {
            ExtensionResponse ast = new ExtensionAst().validate(node("statements", request.get("statements")));
            if (!ast.ok()) throw new Failure(ast.error());
            Session session = new Session(map(request.get("catalogSnapshot")));
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> statement : nodes(ast.data().get("statements"))) result.add(session.statement(statement));
            return node("statements", result, "diagnostics", List.of());
        });
    }
    private static final class Session {
        final Map<String, List<Map<String, Object>>> catalog = new LinkedHashMap<>();
        Session(Map<String, Object> snapshot) {
            for (Map<String, Object> table : nodes(snapshot.get("tables"))) {
                String name = text(table, "name").toLowerCase(Locale.ROOT);
                if (catalog.putIfAbsent(name, columns(nodes(table.get("columns")))) != null)
                    throw fail("SEMANTIC", "DUPLICATE_TABLE", "Catalog 表名重复：" + name, null);
            }
        }
        List<Map<String, Object>> columns(List<Map<String, Object>> input) {
            if (input.isEmpty()) throw fail("SEMANTIC", "INVALID_SCHEMA", "表至少需要一列", null);
            List<Map<String, Object>> result = new ArrayList<>(); Set<String> names = new HashSet<>();
            for (Map<String, Object> column : input) {
                String name = text(column, "name").toLowerCase(Locale.ROOT), type = text(column, "dataType").toUpperCase(Locale.ROOT);
                if (!names.add(name)) throw fail("SEMANTIC", "DUPLICATE_COLUMN", "重复列：" + name, column);
                if (!SqlTypes.TYPES.contains(type)) throw fail("SEMANTIC", "UNSUPPORTED_TYPE", "不支持的数据类型：" + type, column);
                Object nullable = column.getOrDefault("nullable", true);
                if (!(nullable instanceof Boolean)) throw new IllegalArgumentException("nullable");
                result.add(node("name", name, "dataType", type, "nullable", nullable));
            }
            return result;
        }
        List<Map<String, Object>> table(String name, Map<String, Object> owner) {
            if (!catalog.containsKey(name)) throw fail("SEMANTIC", "TABLE_NOT_FOUND", "表不存在：" + name, owner);
            return catalog.get(name);
        }
        Map<String, Object> statement(Map<String, Object> s) {
            String kind = text(s, "kind");
            if (kind.equals("SelectStmt")) return select(s);
            String name = text(s, "table");
            if (kind.equals("CreateTableStmt")) {
                if (catalog.containsKey(name)) throw fail("SEMANTIC", "DUPLICATE_TABLE", "表已存在：" + name, s);
                List<Map<String, Object>> schema = columns(nodes(s.get("columns"))); catalog.put(name, schema);
                s.put("resolvedTable", node("name", name, "columns", copy(schema))); return s;
            }
            List<Map<String, Object>> schema = table(name, s), scope = scope(name, schema, false);
            s.put("resolvedTable", node("name", name, "columns", copy(schema)));
            if (kind.equals("InsertStmt")) {
                List<String> names = strings(s.get("columns")); Set<String> unique = new HashSet<>();
                for (String column : names) { column(schema, column, s); if (!unique.add(column)) throw fail("SEMANTIC", "DUPLICATE_COLUMN", "重复插入列", s); }
                for (Map<String, Object> col : schema) if (!unique.contains(col.get("name")) && !Boolean.TRUE.equals(col.get("nullable")))
                    throw fail("SEMANTIC", "NOT_NULL_VIOLATION", "遗漏 NOT NULL 列：" + col.get("name"), s);
                List<List<Map<String, Object>>> rows = new ArrayList<>();
                for (Object raw : (List<?>) s.get("rows")) {
                    List<Map<String, Object>> values = nodes(raw), typed = new ArrayList<>();
                    if (values.size() != names.size()) throw fail("SEMANTIC", "VALUE_COUNT", "列数与值数不同", s);
                    for (int i = 0; i < values.size(); i++) {
                        Map<String, Object> value = expression(values.get(i), List.of(), false);
                        assignment(value, column(schema, names.get(i), s)); typed.add(value);
                    }
                    rows.add(typed);
                }
                s.put("rows", rows);
            } else if (kind.equals("UpdateStmt")) {
                Set<String> unique = new HashSet<>();
                for (Map<String, Object> assignment : nodes(s.get("assignments"))) {
                    String column = text(assignment, "column");
                    if (!unique.add(column)) throw fail("SEMANTIC", "DUPLICATE_COLUMN", "SET 中的列重复", assignment);
                    Map<String, Object> value = expression(map(assignment.get("value")), scope, false);
                    assignment(value, column(schema, column, assignment)); assignment.put("value", value);
                }
            } else if (!kind.equals("DeleteStmt")) throw fail("SEMANTIC", "UNSUPPORTED_STATEMENT", "不支持的语句", s);
            if (!kind.equals("InsertStmt")) s.put("where", predicate(map(s.get("where")), scope, false));
            return s;
        }
        Map<String, Object> select(Map<String, Object> s) {
            List<Map<String, Object>> scope = new ArrayList<>(); Set<String> aliases = new HashSet<>();
            addTable(map(s.get("from")), scope, aliases, false);
            for (Map<String, Object> join : nodes(s.get("joins"))) {
                addTable(map(join.get("table")), scope, aliases, text(join, "joinType").equals("LEFT"));
                join.put("on", predicate(map(join.get("on")), scope, false));
            }
            s.put("where", predicate(map(s.get("where")), scope, false));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> item : nodes(s.get("items"))) {
                Map<String, Object> e = map(item.get("expression"));
                if (text(e, "kind").equals("StarExpr")) {
                    if (item.get("alias") != null) throw fail("SEMANTIC", "INVALID_STAR", "通配符不能设置别名", item);
                    int before = items.size();
                    for (Map<String, Object> col : scope) if (e.get("qualifier") == null || e.get("qualifier").equals(col.get("table"))) {
                        Map<String, Object> id = node("kind", "IdentifierExpr", "name", col.get("column"), "qualifier", col.get("table"), "loc", copy(e.get("loc")));
                        items.add(node("kind", "SelectItem", "alias", null, "name", col.get("column"), "expression", expression(id, scope, true), "loc", copy(item.get("loc"))));
                    }
                    if (before == items.size()) throw fail("SEMANTIC", "UNKNOWN_QUALIFIER", "通配符限定表不存在", e);
                } else {
                    item.put("expression", expression(e, scope, true));
                    item.put("name", item.get("alias") != null ? item.get("alias") : e.get("kind").equals("IdentifierExpr") ? e.get("name") : "expr" + (items.size() + 1));
                    items.add(item);
                }
            }
            s.put("items", items);
            List<Map<String, Object>> groups = new ArrayList<>();
            if (s.get("groupBy") != null) {
                for (Map<String, Object> e : nodes(map(s.get("groupBy")).get("expressions"))) groups.add(expression(e, scope, false));
                map(s.get("groupBy")).put("expressions", groups);
            }
            s.put("having", predicate(map(s.get("having")), scope, true));
            List<Map<String, Object>> sorts = s.get("orderBy") == null ? List.of() : nodes(map(s.get("orderBy")).get("items"));
            for (Map<String, Object> sort : sorts) {
                Map<String, Object> e = map(sort.get("expression"));
                if (text(e, "kind").equals("IdentifierExpr") && e.get("qualifier") == null) {
                    String name = text(e, "name");
                    List<Map<String, Object>> matches = items.stream().filter(i -> name.equals(i.get("alias"))).toList();
                    if (matches.size() > 1) throw fail("SEMANTIC", "AMBIGUOUS_ALIAS", "排序别名不唯一：" + name, e);
                    if (matches.size() == 1) e = map(copy(matches.get(0).get("expression")));
                } else if (text(e, "kind").equals("LiteralExpr") && Set.of("INT", "BIGINT").contains(text(e, "literalType"))) {
                    int position;
                    try { position = SqlTypes.decimal(e.get("value")).intValueExact(); } catch (ArithmeticException ex) { position = 0; }
                    if (position < 1 || position > items.size()) throw fail("SEMANTIC", "ORDER_POSITION", "ORDER BY 序号超出选择列范围", e);
                    e = map(copy(items.get(position - 1).get("expression")));
                }
                sort.put("expression", expression(e, scope, true));
            }
            List<Map<String, Object>> aggregates = new ArrayList<>();
            for (Map<String, Object> item : items) AggregateSupport.collect(map(item.get("expression")), aggregates);
            AggregateSupport.collect(map(s.get("having")), aggregates);
            for (Map<String, Object> sort : sorts) AggregateSupport.collect(map(sort.get("expression")), aggregates);
            boolean grouped = s.get("groupBy") != null || !aggregates.isEmpty();
            if (!grouped && s.get("having") != null) throw fail("SEMANTIC", "HAVING_WITHOUT_GROUP", "HAVING 需要分组或聚合", s);
            if (grouped) {
                for (Map<String, Object> item : items) AggregateSupport.lower(map(item.get("expression")), groups, aggregates, "SEMANTIC");
                for (Map<String, Object> sort : sorts) AggregateSupport.lower(map(sort.get("expression")), groups, aggregates, "SEMANTIC");
                if (s.get("having") != null) AggregateSupport.lower(map(s.get("having")), groups, aggregates, "SEMANTIC");
            }
            s.put("aggregation", grouped ? node("groups", groups, "aggregates", aggregates) : null);
            return s;
        }
        void addTable(Map<String, Object> ref, List<Map<String, Object>> target, Set<String> aliases, boolean nullable) {
            String alias = text(ref, "alias");
            if (!aliases.add(alias)) throw fail("SEMANTIC", "DUPLICATE_ALIAS", "表别名重复：" + alias, ref);
            List<Map<String, Object>> bindings = scope(alias, table(text(ref, "table"), ref), nullable);
            ref.put("resolvedSchema", bindings); target.addAll(bindings);
        }
        List<Map<String, Object>> scope(String alias, List<Map<String, Object>> schema, boolean nullable) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> c : schema) result.add(node("table", alias, "column", c.get("name"), "dataType", c.get("dataType"), "nullable", nullable || Boolean.TRUE.equals(c.get("nullable"))));
            return result;
        }
        Map<String, Object> column(List<Map<String, Object>> schema, String name, Map<String, Object> owner) {
            return schema.stream().filter(c -> name.equals(c.get("name"))).findFirst()
                    .orElseThrow(() -> fail("SEMANTIC", "COLUMN_NOT_FOUND", "列不存在：" + name, owner));
        }
        void assignment(Map<String, Object> value, Map<String, Object> column) {
            if (!SqlTypes.assignable(text(value, "inferredType"), text(column, "dataType")))
                throw fail("SEMANTIC", "TYPE_MISMATCH", "赋值类型不兼容：" + value.get("inferredType") + " → " + column.get("dataType"), value);
            if (text(value, "inferredType").equals("NULL") && !Boolean.TRUE.equals(column.get("nullable")))
                throw fail("SEMANTIC", "NOT_NULL_VIOLATION", "不能向 NOT NULL 列赋 NULL", value);
        }
        Map<String, Object> predicate(Map<String, Object> e, List<Map<String, Object>> scope, boolean aggregate) {
            if (e == null) return null;
            e = expression(e, scope, aggregate); requireBoolean(e); return e;
        }
        void requireBoolean(Map<String, Object> e) {
            if (!Set.of("BOOL", "NULL").contains(text(e, "inferredType"))) throw fail("SEMANTIC", "TYPE_MISMATCH", "条件必须为 BOOL 或 NULL", e);
        }
        Map<String, Object> expression(Map<String, Object> e, List<Map<String, Object>> scope, boolean aggregate) {
            String kind = text(e, "kind"), type; boolean nullable = false;
            switch (kind) {
                case "NullLiteralExpr" -> { type = "NULL"; nullable = true; }
                case "LiteralExpr" -> {
                    type = text(e, "literalType");
                    e.put("value", SqlTypes.coerce(e.get("value"), type, false, "SEMANTIC", e));
                }
                case "IdentifierExpr" -> {
                    List<Map<String, Object>> found = scope.stream().filter(c -> e.get("name").equals(c.get("column"))
                            && (e.get("qualifier") == null || e.get("qualifier").equals(c.get("table")))).toList();
                    if (found.isEmpty()) throw fail("SEMANTIC", "COLUMN_NOT_FOUND", "列不存在：" + e.get("name"), e);
                    if (found.size() > 1) throw fail("SEMANTIC", "AMBIGUOUS_COLUMN", "列名有歧义，请使用表别名限定：" + e.get("name"), e);
                    e.put("binding", copy(found.get(0))); type = text(found.get(0), "dataType"); nullable = Boolean.TRUE.equals(found.get(0).get("nullable"));
                }
                case "AggregateExpr" -> {
                    if (!aggregate) throw fail("SEMANTIC", "INVALID_AGGREGATE", "此处不允许聚合或嵌套聚合", e);
                    String function = text(e, "function"); Map<String, Object> argument = map(e.get("argument"));
                    if (!Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(function)) throw fail("SEMANTIC", "UNSUPPORTED_FUNCTION", "不支持的聚合函数", e);
                    if (text(argument, "kind").equals("StarExpr")) {
                        if (!function.equals("COUNT") || argument.get("qualifier") != null) throw fail("SEMANTIC", "INVALID_AGGREGATE", "只有 COUNT(*) 可以使用通配符", e);
                        type = "BIGINT";
                    } else {
                        expression(argument, scope, false); String argumentType = text(argument, "inferredType");
                        if (Set.of("SUM", "AVG").contains(function) && !SqlTypes.numeric(argumentType) && !argumentType.equals("NULL"))
                            throw fail("SEMANTIC", "TYPE_MISMATCH", "SUM/AVG 需要数值参数", e);
                        type = switch (function) {
                            case "COUNT" -> "BIGINT";
                            case "AVG" -> "DECIMAL";
                            case "SUM" -> argumentType.equals("DECIMAL") || argumentType.equals("NULL") ? "DECIMAL" : "BIGINT";
                            default -> argumentType;
                        };
                    }
                    nullable = !function.equals("COUNT");
                }
                case "IsNullExpr", "UnaryExpr" -> {
                    Map<String, Object> operand = expression(map(e.get("operand")), scope, aggregate);
                    if (kind.equals("IsNullExpr")) type = "BOOL";
                    else {
                        String operator = text(e, "operator"); nullable = Boolean.TRUE.equals(operand.get("nullable"));
                        if (operator.equals("NOT")) { requireBoolean(operand); type = "BOOL"; }
                        else if (Set.of("+", "-").contains(operator)) {
                            type = text(operand, "inferredType");
                            if (!SqlTypes.numeric(type) && !type.equals("NULL")) throw fail("SEMANTIC", "TYPE_MISMATCH", "一元正负号需要数值", e);
                        } else throw fail("SEMANTIC", "UNSUPPORTED_OPERATOR", "不支持的一元运算符", e);
                    }
                }
                case "BinaryExpr" -> {
                    Map<String, Object> left = expression(map(e.get("left")), scope, aggregate), right = expression(map(e.get("right")), scope, aggregate);
                    String a = text(left, "inferredType"), b = text(right, "inferredType"), op = text(e, "operator");
                    nullable = Boolean.TRUE.equals(left.get("nullable")) || Boolean.TRUE.equals(right.get("nullable"));
                    if (Set.of("AND", "OR").contains(op)) { requireBoolean(left); requireBoolean(right); type = "BOOL"; }
                    else if (Set.of("=", "!=", ">", ">=", "<", "<=").contains(op)) {
                        if (!a.equals("NULL") && !b.equals("NULL") && !a.equals(b) && !(SqlTypes.numeric(a) && SqlTypes.numeric(b)))
                            throw fail("SEMANTIC", "TYPE_MISMATCH", "比较类型不兼容", e);
                        type = "BOOL";
                    } else if (Set.of("+", "-", "*", "/").contains(op)) {
                        if ((!SqlTypes.numeric(a) && !a.equals("NULL")) || (!SqlTypes.numeric(b) && !b.equals("NULL")))
                            throw fail("SEMANTIC", "TYPE_MISMATCH", "算术运算需要数值参数", e);
                        type = op.equals("/") ? "DECIMAL" : SqlTypes.widen(a, b);
                    } else throw fail("SEMANTIC", "UNSUPPORTED_OPERATOR", "不支持的二元运算符", e);
                }
                default -> throw fail("SEMANTIC", "UNSUPPORTED_EXPRESSION", "表达式不支持或通配符位置无效", e);
            }
            e.put("inferredType", type); e.put("nullable", nullable); return e;
        }
    }
}
