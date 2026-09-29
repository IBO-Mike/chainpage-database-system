package com.chainpage.sqlcompiler.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 语义检查与计划生成共享的聚合表达式匹配、去重及结果槽位转换。 */
final class AggregateSupport {
    // 只比较表达式结构和绑定，不比较 loc 等标注；同一表达式在 SQL 的不同位置仍可复用结果。
    static Object key(Map<String, Object> e) {
        String kind = text(e, "kind");
        return switch (kind) {
            case "IdentifierExpr" -> { Map<String, Object> b = map(e.get("binding")); yield List.of(kind, b.get("table"), b.get("column")); }
            case "NullLiteralExpr" -> List.of(kind);
            case "LiteralExpr" -> List.of(kind, e.get("literalType"), SqlTypes.groupValue(e.get("value")));
            case "StarExpr" -> List.of(kind);
            case "UnaryExpr" -> List.of(kind, e.get("operator"), key(map(e.get("operand"))));
            case "IsNullExpr" -> List.of(kind, e.get("negated"), key(map(e.get("operand"))));
            case "BinaryExpr" -> List.of(kind, e.get("operator"), key(map(e.get("left"))), key(map(e.get("right"))));
            case "AggregateExpr" -> List.of(kind, e.get("function"), key(map(e.get("argument"))));
            default -> throw fail("PLANNER", "UNSUPPORTED_EXPRESSION", "不支持的聚合表达式", e);
        };
    }
    static void collect(Map<String, Object> e, List<Map<String, Object>> aggregates) {
        if (e == null) return;
        if (text(e, "kind").equals("AggregateExpr")) {
            // SELECT、HAVING、ORDER BY 重复出现的聚合共用一个槽位，索引由首次出现的顺序确定。
            if (aggregates.stream().noneMatch(a -> key(a).equals(key(e)))) aggregates.add(e);
            return;
        }
        for (String field : List.of("left", "right", "operand")) if (e.get(field) != null) collect(map(e.get(field)), aggregates);
    }
    static Map<String, Object> lower(Map<String, Object> e, List<Map<String, Object>> groups,
                                      List<Map<String, Object>> aggregates, String stage) {
        // 先匹配整个分组/聚合表达式，再递归处理运算子项，使上层表达式读取聚合后的结果。
        for (int i = 0; i < groups.size(); i++) if (key(groups.get(i)).equals(key(e))) return slot(e, "g" + i);
        for (int i = 0; i < aggregates.size(); i++) if (key(aggregates.get(i)).equals(key(e))) return slot(e, "a" + i);
        // 聚合后已没有逐行源数据；未匹配分组键的裸列不能继续向执行器传递。
        // stage 由调用方传入，使语义预检查和计划生成分别保留所属阶段的错误信息。
        if (text(e, "kind").equals("IdentifierExpr")) throw fail(stage, "NOT_GROUPED", "非聚合列必须出现在 GROUP BY 中", e);
        Map<String, Object> result = map(copy(e));
        for (String field : List.of("left", "right", "operand"))
            if (e.get(field) != null) result.put(field, lower(map(e.get(field)), groups, aggregates, stage));
        return result;
    }
    private static Map<String, Object> slot(Map<String, Object> e, String slot) {
        return node("kind", "SlotRefExpr", "slot", slot, "inferredType", e.get("inferredType"),
                "nullable", e.get("nullable"), "loc", copy(e.get("loc")));
    }
}
