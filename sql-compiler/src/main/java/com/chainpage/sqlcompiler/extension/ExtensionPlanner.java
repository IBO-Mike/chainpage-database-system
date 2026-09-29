package com.chainpage.sqlcompiler.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 输出遵循数据库执行器规约；表达式中间表示仅用于本阶段降级。 */
public final class ExtensionPlanner {
    public ExtensionResponse buildPlan(Map<String, Object> request) {
        return ExtensionResponse.run("PLANNER", () -> {
            // 内部计划会复用 AST 中的表达式，先深拷贝以隔离调用者持有的语义分析结果。
            List<Map<String, Object>> statements = nodes(copy(request.get("statements")));
            List<Map<String, Object>> plans = new ArrayList<>();
            for (Map<String, Object> statement : statements) {
                annotations(statement);
                // 先构造保留表达式的内部计划，再降为公开算子；最终契约检查拦截结构不匹配。
                Map<String, Object> plan = StandardPlans.lower(statement(statement));
                com.chainpage.sqlcompiler.planner.PlanContract.validate(plan);
                plans.add(plan);
            }
            return node("plans", plans);
        });
    }
    private static void annotations(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> n = map(raw);
            // COUNT(*) 的 StarExpr 只是参数占位符，不会作为独立表达式求值，因此不要求类型标注。
            if (n.get("kind") instanceof String kind && kind.endsWith("Expr") && !kind.equals("StarExpr")) {
                if (!(n.get("inferredType") instanceof String) || !(n.get("nullable") instanceof Boolean)
                        || kind.equals("IdentifierExpr") && !(n.get("binding") instanceof Map<?, ?>))
                    throw fail("PLANNER", "MISSING_ANNOTATION", "表达式缺少语义标注", n);
            }
            for (Object child : n.values()) annotations(child);
        } else if (value instanceof List<?> list) for (Object child : list) annotations(child);
    }
    private static Map<String, Object> statement(Map<String, Object> s) {
        String kind = text(s, "kind");
        if (kind.equals("SelectStmt")) return select(s);
        if (!List.of("CreateTableStmt", "InsertStmt", "UpdateStmt", "DeleteStmt").contains(kind))
            throw fail("PLANNER", "UNSUPPORTED_STATEMENT", "不支持的语句：" + kind, s);
        if (!(s.get("resolvedTable") instanceof Map<?, ?>)) throw fail("PLANNER", "MISSING_SCHEMA", "缺少 resolvedTable", s);
        Map<String, Object> plan = plan(kind.replace("Stmt", ""), List.of(), List.of());
        plan.put("table", s.get("table")); plan.put("loc", copy(s.get("loc")));
        switch (kind) {
            case "CreateTableStmt" -> plan.put("columns", map(s.get("resolvedTable")).get("columns"));
            case "InsertStmt" -> { plan.put("columns", s.get("columns")); plan.put("rows", s.get("rows")); }
            case "UpdateStmt" -> { plan.put("assignments", s.get("assignments")); plan.put("predicate", s.get("where")); }
            case "DeleteStmt" -> plan.put("predicate", s.get("where"));
        }
        return plan;
    }
    private static Map<String, Object> select(Map<String, Object> s) {
        if (!s.containsKey("aggregation")) throw fail("PLANNER", "MISSING_ANNOTATION", "缺少 aggregation 标注", s);
        // 按数据流自底向上构造：扫描/连接 -> WHERE -> 聚合/HAVING -> 排序 -> 投影。
        Map<String, Object> input = scan(map(s.get("from")));
        // 每次把已有输入放在左侧、新表放在右侧，形成左深连接树，并按相同顺序拼接列结构。
        for (Map<String, Object> j : nodes(s.get("joins"))) {
            Map<String, Object> right = scan(map(j.get("table")));
            List<Map<String, Object>> schema = new ArrayList<>(nodes(input.get("schema"))); schema.addAll(nodes(right.get("schema")));
            Map<String, Object> joined = plan("Join", List.of(input, right), schema);
            joined.putAll(node("joinType", j.get("joinType"), "predicate", j.get("on"))); input = joined;
        }
        if (s.get("where") != null) input = filter(input, map(s.get("where")));
        Map<String, Object> aggregation = map(s.get("aggregation"));
        List<Map<String, Object>> groups = aggregation == null ? List.of() : nodes(aggregation.get("groups"));
        List<Map<String, Object>> aggregates = aggregation == null ? List.of() : nodes(aggregation.get("aggregates"));
        if (aggregation != null) {
            // gN 表示分组键，aN 表示聚合值；聚合后的表达式改为引用这些结果槽位。
            // 槽位名仅用于内部衔接，StandardPlans 会转换为执行器可识别的列名。
            List<Map<String, Object>> schema = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) schema.add(output("g" + i, groups.get(i)));
            for (int i = 0; i < aggregates.size(); i++) schema.add(output("a" + i, aggregates.get(i)));
            Map<String, Object> grouped = plan("Aggregate", List.of(input), schema);
            grouped.putAll(node("groupBy", groups, "aggregates", aggregates)); input = grouped;
            // HAVING 筛选聚合结果，因此必须位于 Aggregate 之上，并重写其中的分组/聚合引用。
            if (s.get("having") != null) input = filter(input, AggregateSupport.lower(map(s.get("having")), groups, aggregates, "PLANNER"));
        }
        // 排序先于最终投影，避免投影裁掉 ORDER BY 仍需读取的列。
        if (s.get("orderBy") != null) {
            List<Map<String, Object>> keys = new ArrayList<>();
            for (Map<String, Object> sort : nodes(map(s.get("orderBy")).get("items"))) {
                Map<String, Object> key = map(copy(sort));
                if (aggregation != null) key.put("expression", AggregateSupport.lower(map(sort.get("expression")), groups, aggregates, "PLANNER"));
                keys.add(key);
            }
            Map<String, Object> sorted = plan("Sort", List.of(input), nodes(input.get("schema"))); sorted.put("keys", keys); input = sorted;
        }
        List<Map<String, Object>> items = new ArrayList<>(), schema = new ArrayList<>();
        for (Map<String, Object> item : nodes(s.get("items"))) {
            Map<String, Object> e = map(item.get("expression"));
            if (aggregation != null) e = AggregateSupport.lower(e, groups, aggregates, "PLANNER");
            items.add(node("name", text(item, "name"), "expression", e)); schema.add(output(text(item, "name"), e));
        }
        Map<String, Object> projected = plan("Project", List.of(input), schema); projected.put("items", items); return projected;
    }
    private static Map<String, Object> scan(Map<String, Object> ref) {
        if (!(ref.get("resolvedSchema") instanceof List<?>)) throw fail("PLANNER", "MISSING_SCHEMA", "缺少表 schema", ref);
        // 列名带语义绑定中的表别名，自连接时可区分同一物理表的不同输入。
        List<Map<String, Object>> schema = new ArrayList<>();
        for (Map<String, Object> binding : nodes(ref.get("resolvedSchema")))
            schema.add(node("name", binding.get("table") + "." + binding.get("column"), "dataType", binding.get("dataType"), "nullable", binding.get("nullable")));
        Map<String, Object> plan = plan("SeqScan", List.of(), schema); plan.putAll(node("table", ref.get("table"), "alias", ref.get("alias"))); return plan;
    }
    private static Map<String, Object> output(String name, Map<String, Object> e) {
        return node("name", name, "dataType", e.get("inferredType"), "nullable", e.get("nullable"));
    }
    private static Map<String, Object> plan(String kind, List<Map<String, Object>> children, List<Map<String, Object>> schema) {
        return node("kind", kind, "children", children, "schema", copy(schema));
    }
    private static Map<String, Object> filter(Map<String, Object> child, Map<String, Object> predicate) {
        Map<String, Object> plan = plan("Filter", List.of(child), nodes(child.get("schema"))); plan.put("predicate", predicate); return plan;
    }
}
