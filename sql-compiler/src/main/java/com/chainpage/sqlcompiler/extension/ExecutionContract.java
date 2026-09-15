package com.chainpage.sqlcompiler.extension;

import java.util.List;
import java.util.Map;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/** 共享结构校验后检查实际下游的算子/类型能力。 */
final class ExecutionContract {
    static void check(Map<String, Object> plan, ExtensionExecutor executor) {
        // 未知算子先报告能力错误，而不是尝试执行或忽略。
        capabilities(plan, executor);
        try { com.chainpage.sqlcompiler.planner.PlanContract.validate(plan); }
        catch (IllegalArgumentException e) { throw fail("EXECUTOR", "INVALID_PLAN", e.getMessage(), plan); }
    }
    private static void capabilities(Map<String, Object> plan, ExtensionExecutor executor) {
        String kind = text(plan, "kind");
        if (!executor.supportedPlanKinds().contains(kind))
            throw fail("EXECUTOR", "UNSUPPORTED_PLAN", "执行器不支持 " + kind, plan);
        for (Map<String, Object> column : nodes(plan.get("schema"))) type(text(column, "dataType"), executor, plan);
        if (kind.equals("CreateTable")) for (Map<String, Object> column : nodes(plan.get("columns"))) type(text(column, "dataType"), executor, plan);
        expressions(plan, executor);
        for (Map<String, Object> child : nodes(plan.get("children"))) capabilities(child, executor);
    }
    private static void type(String type, ExtensionExecutor executor, Map<String, Object> owner) {
        if (!type.equals("NULL") && !executor.supportedTypes().contains(type))
            throw fail("EXECUTOR", "UNSUPPORTED_TYPE", "执行器不支持类型：" + type, owner);
    }
    private static void expressions(Object value, ExtensionExecutor executor) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> n = map(raw);
            if (n.get("kind") instanceof String kind && kind.endsWith("Expr")) {
                type(text(n, "inferredType"), executor, n);
                if (kind.equals("LiteralExpr")) SqlTypes.coerce(n.get("value"), text(n, "inferredType"), false, "EXECUTOR", n);
            }
            for (var entry : n.entrySet()) if (!entry.getKey().equals("children")) expressions(entry.getValue(), executor);
        } else if (value instanceof List<?> list) for (Object child : list) expressions(child, executor);
    }
}
