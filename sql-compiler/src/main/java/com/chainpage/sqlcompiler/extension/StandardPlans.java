package com.chainpage.sqlcompiler.extension;

import java.util.*;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 将语义阶段的表达式计划降为 database-engine-spec 定义的算子。 */
final class StandardPlans {
    static Map<String, Object> lower(Map<String, Object> source) {
        return lower(source, new LinkedHashMap<>());
    }
    private static Map<String, Object> lower(Map<String, Object> p, Map<String, String> slots) {
        String kind = text(p, "kind");
        List<Map<String, Object>> children = new ArrayList<>();
        for (Map<String, Object> child : nodes(p.get("children"))) children.add(lower(child, slots));
        Map<String, Object> out = node("kind", kind, "children", children, "schema", schema(nodes(p.get("schema")), slots));
        switch (kind) {
            case "CreateTable" -> out.putAll(node("table", p.get("table"), "columns", copy(p.get("columns"))));
            case "Insert" -> {
                List<?> rows = (List<?>) p.get("rows");
                if (rows.size() != 1) throw unsupported("Insert 接口仅支持一组 values；请拆分为多条 INSERT", p);
                out.putAll(node("table", p.get("table"), "columns", copy(p.get("columns")), "values", copy(rows.get(0))));
            }
            case "Update" -> out.putAll(node("table", p.get("table"), "assignments", copy(p.get("assignments")), "predicate", copy(p.get("predicate"))));
            case "Delete" -> out.putAll(node("table", p.get("table"), "predicate", copy(p.get("predicate"))));
            case "SeqScan" -> out.put("table", p.get("table"));
            case "Filter" -> out.put("predicate", rewrite(p.get("predicate"), slots));
            case "Join" -> {
                Map<String, Object> e = map(p.get("predicate"));
                if (!"INNER".equals(p.get("joinType")) || !"BinaryExpr".equals(e.get("kind")) || !"=".equals(e.get("operator")))
                    throw unsupported("Join 接口仅支持 INNER 等值列连接", p);
                String left = column(map(e.get("left")), slots), right = column(map(e.get("right")), slots);
                Set<String> l = names(children.get(0)), r = names(children.get(1));
                if (l.contains(right) && r.contains(left)) { String tmp = left; left = right; right = tmp; }
                if (!l.contains(left) || !r.contains(right)) throw unsupported("连接键必须分别来自左右子计划", p);
                out.putAll(node("leftKey", left, "rightKey", right));
            }
            case "Aggregate" -> {
                List<String> keys = new ArrayList<>(); List<Map<String, Object>> aggregates = new ArrayList<>(), schema = new ArrayList<>();
                List<Map<String, Object>> groups = nodes(p.get("groupBy")), expressions = nodes(p.get("aggregates"));
                for (int i = 0; i < groups.size(); i++) {
                    String key = column(groups.get(i), slots); keys.add(key); slots.put("g" + i, key);
                    schema.add(node("name", key, "dataType", groups.get(i).get("inferredType")));
                }
                for (int i = 0; i < expressions.size(); i++) {
                    Map<String, Object> e = expressions.get(i); String fn = text(e, "function"), alias = "a" + i;
                    if (!Set.of("COUNT", "SUM").contains(fn)) throw unsupported("GroupBy 接口仅支持 COUNT/SUM", e);
                    Map<String, Object> arg = map(e.get("argument"));
                    String key = "StarExpr".equals(arg.get("kind")) ? "*" : column(arg, slots);
                    aggregates.add(node("function", fn, "column", key, "alias", alias)); slots.put(alias, alias);
                    schema.add(node("name", alias, "dataType", e.get("inferredType")));
                }
                out.putAll(node("kind", "GroupBy", "keys", keys, "aggregates", aggregates, "schema", schema));
            }
            case "Sort" -> {
                List<Map<String, Object>> keys = new ArrayList<>();
                for (Map<String, Object> key : nodes(p.get("keys"))) {
                    String direction = text(key, "direction");
                    String defaultNulls = direction.equals("ASC") ? "LAST" : "FIRST";
                    if (!defaultNulls.equals(key.get("nulls"))) throw unsupported("Sort 接口不支持自定义 NULLS 顺序", key);
                    keys.add(node("column", column(map(key.get("expression")), slots), "direction", direction));
                }
                out.put("keys", keys); out.put("schema", copy(children.get(0).get("schema")));
            }
            case "Project" -> {
                List<String> columns = new ArrayList<>(); List<Map<String, Object>> schema = new ArrayList<>();
                for (Map<String, Object> item : nodes(p.get("items"))) {
                    Map<String, Object> e = map(item.get("expression")); String col = column(e, slots);
                    columns.add(col); schema.add(node("name", item.get("name"), "dataType", e.get("inferredType")));
                }
                out.putAll(node("columns", columns, "schema", schema));
            }
            default -> throw unsupported("未知计划节点：" + kind, p);
        }
        return out;
    }
    private static Set<String> names(Map<String, Object> p) {
        Set<String> result = new HashSet<>(); for (Map<String, Object> c : nodes(p.get("schema"))) result.add(text(c, "name")); return result;
    }
    private static List<Map<String, Object>> schema(List<Map<String, Object>> columns, Map<String, String> slots) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> c : columns) result.add(node("name", slots.getOrDefault(text(c, "name"), text(c, "name")), "dataType", c.get("dataType")));
        return result;
    }
    private static String column(Map<String, Object> e, Map<String, String> slots) {
        if ("IdentifierExpr".equals(e.get("kind"))) { Map<String, Object> b = map(e.get("binding")); return text(b, "table") + "." + text(b, "column"); }
        if ("SlotRefExpr".equals(e.get("kind")) && slots.containsKey(e.get("slot"))) return slots.get(e.get("slot"));
        throw unsupported("该计划字段只接受列引用，不支持计算表达式", e);
    }
    private static Object rewrite(Object value, Map<String, String> slots) {
        if (value instanceof List<?> list) { List<Object> result = new ArrayList<>(); for (Object v : list) result.add(rewrite(v, slots)); return result; }
        if (!(value instanceof Map<?, ?>)) return value;
        Map<String, Object> e = map(value), result = new LinkedHashMap<>();
        if ("SlotRefExpr".equals(e.get("kind"))) {
            String col = column(e, slots);
            return node("kind", "IdentifierExpr", "name", col, "inferredType", e.get("inferredType"), "loc", copy(e.get("loc")),
                    "binding", node("table", "_group", "column", col, "dataType", e.get("inferredType")));
        }
        e.forEach((k,v) -> result.put(k, rewrite(v, slots))); return result;
    }
    private static Failure unsupported(String message, Map<String, Object> owner) { return fail("PLANNER", "UNSUPPORTED_PLAN", message, owner); }
}
