package com.chainpage.sqlcompiler.extension;

import java.util.*;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 将语义阶段的表达式计划降为 database-engine-spec 定义的算子。 */
final class StandardPlans {
    /** 每棵计划使用独立的槽位映射，避免多语句编译时聚合结果名互相污染。 */
    static Map<String, Object> lower(Map<String, Object> source) {
        return lower(source, new LinkedHashMap<>());
    }
    private static Map<String, Object> lower(Map<String, Object> p, Map<String, String> slots) {
        String kind = text(p, "kind");
        List<Map<String, Object>> children = new ArrayList<>();
        // 必须先处理子计划：Aggregate 填入的 gN/aN 映射会被上层 HAVING、Sort 和 Project 使用。
        for (Map<String, Object> child : nodes(p.get("children"))) children.add(lower(child, slots));
        // 按公开协议重新组装字段，不直接复制内部节点，避免泄漏 items、alias 等内部计划字段。
        Map<String, Object> out = node("kind", kind, "children", children, "schema", schema(nodes(p.get("schema")), slots));
        switch (kind) {
            case "CreateTable" -> out.putAll(node("table", p.get("table"), "columns", copy(p.get("columns"))));
            case "Insert" -> {
                List<?> rows = (List<?>) p.get("rows");
                // 解析器支持多行 VALUES，但当前执行接口只有单组 values，不能静默丢弃后续行。
                if (rows.size() != 1) throw unsupported("Insert 接口仅支持一组 values；请拆分为多条 INSERT", p);
                out.putAll(node("table", p.get("table"), "columns", copy(p.get("columns")), "values", copy(rows.get(0))));
            }
            case "Update" -> out.putAll(node("table", p.get("table"), "assignments", copy(p.get("assignments")), "predicate", copy(p.get("predicate"))));
            case "Delete" -> out.putAll(node("table", p.get("table"), "predicate", copy(p.get("predicate"))));
            case "SeqScan" -> out.put("table", p.get("table"));
            case "Filter" -> out.put("predicate", rewrite(p.get("predicate"), slots));
            case "Join" -> {
                // leftKey/rightKey 只能表达 INNER 等值列连接，其他 ON 条件或外连接需扩展执行协议。
                Map<String, Object> e = map(p.get("predicate"));
                if (!"INNER".equals(p.get("joinType")) || !"BinaryExpr".equals(e.get("kind")) || !"=".equals(e.get("operator")))
                    throw unsupported("Join 接口仅支持 INNER 等值列连接", p);
                String left = column(map(e.get("left")), slots), right = column(map(e.get("right")), slots);
                Set<String> l = names(children.get(0)), r = names(children.get(1));
                // ON b.id = a.id 与 ON a.id = b.id 等价，键的方向按左右子计划归属统一。
                if (l.contains(right) && r.contains(left)) { String tmp = left; left = right; right = tmp; }
                if (!l.contains(left) || !r.contains(right)) throw unsupported("连接键必须分别来自左右子计划", p);
                out.putAll(node("leftKey", left, "rightKey", right));
            }
            case "Aggregate" -> {
                // 内部 Aggregate 转为公开 GroupBy：分组槽位映射回源列，聚合槽位保留 aN 作为输出别名。
                List<String> keys = new ArrayList<>(); List<Map<String, Object>> aggregates = new ArrayList<>(), schema = new ArrayList<>();
                List<Map<String, Object>> groups = nodes(p.get("groupBy")), expressions = nodes(p.get("aggregates"));
                for (int i = 0; i < groups.size(); i++) {
                    String key = column(groups.get(i), slots); keys.add(key); slots.put("g" + i, key);
                    schema.add(node("name", key, "dataType", groups.get(i).get("inferredType")));
                }
                for (int i = 0; i < expressions.size(); i++) {
                    Map<String, Object> e = expressions.get(i); String fn = text(e, "function"), alias = "a" + i;
                    // 语法接受更多聚合函数；只有执行协议已定义的 COUNT/SUM 才能生成可消费计划。
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
                    // 公开排序键没有 nulls 字段，只能保留执行器默认的 ASC NULLS LAST / DESC NULLS FIRST。
                    String defaultNulls = direction.equals("ASC") ? "LAST" : "FIRST";
                    if (!defaultNulls.equals(key.get("nulls"))) throw unsupported("Sort 接口不支持自定义 NULLS 顺序", key);
                    keys.add(node("column", column(map(key.get("expression")), slots), "direction", direction));
                }
                out.put("keys", keys); out.put("schema", copy(children.get(0).get("schema")));
            }
            case "Project" -> {
                // columns 保存输入列名，schema 保存输出名和类型，两者按位置对应以支持 SELECT 别名。
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
        // 列字段只能接收绑定列或已解析的聚合槽位；计算表达式需要新增相应执行能力。
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
            // 将 HAVING 等表达式中的槽位还原为标准列引用；_group 绑定指向分组结果行中的列。
            return node("kind", "IdentifierExpr", "name", col, "inferredType", e.get("inferredType"), "loc", copy(e.get("loc")),
                    "binding", node("table", "_group", "column", col, "dataType", e.get("inferredType")));
        }
        e.forEach((k,v) -> result.put(k, rewrite(v, slots))); return result;
    }
    private static Failure unsupported(String message, Map<String, Object> owner) { return fail("PLANNER", "UNSUPPORTED_PLAN", message, owner); }
}
