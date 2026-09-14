package com.chainpage.sqlcompiler.optimizer;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** 复用第 7 部分规则，遍历标准扩展算子；不改变未知规则域的表达式。 */
final class ExtendedOptimizer {
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object v) { return (Map<String, Object>) v; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> nodes(Object v) { return (List<Map<String, Object>>) v; }
    private static String text(Map<String, Object> v, String k) {
        if (!(v.get(k) instanceof String s) || s.isEmpty()) throw new IllegalArgumentException(k); return s;
    }
    private static Object copy(Object v) {
        if (v instanceof Map<?, ?> m) { Map<String, Object> out = new LinkedHashMap<>(); m.forEach((k,x) -> out.put((String)k, copy(x))); return out; }
        if (v instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object x : l) out.add(copy(x)); return out; } return v;
    }
    public OptimizeResponse optimize(Map<String, Object> plan) {
        if (plan == null) return new Optimizer().optimize(new OptimizeRequest(null));
        try {
            com.chainpage.sqlcompiler.planner.PlanContract.validate(plan);
            Map<String, Object> original = map(copy(plan)); Session session = new Session();
            Map<String, Object> optimized = session.plan(map(copy(original)));
            return OptimizeResponse.success(original, optimized, new ArrayList<>(session.rules));
        } catch (OptimizationFailure failure) { return OptimizeResponse.failure(failure.error); }
        catch (IllegalArgumentException | ClassCastException | NullPointerException exception) {
            return OptimizeResponse.failure(new OptimizerError("OPTIMIZER_INVALID_PLAN", "扩展计划的结构或字段无效", null, null));
        }
    }
    private static final class Session {
        final Set<String> rules = new LinkedHashSet<>();
        final Optimizer optimizer = new Optimizer();
        Map<String, Object> plan(Map<String, Object> input) {
            String kind = text(input, "kind");
            if (!Set.of("CreateTable", "Insert", "Update", "Delete", "SeqScan", "Filter", "Project", "Join", "GroupBy", "Sort").contains(kind))
                throw failure("OPTIMIZER_UNSUPPORTED_PLAN", "不支持该计划节点：" + kind, input);
            List<Map<String, Object>> children = new ArrayList<>();
            for (Map<String, Object> child : nodes(input.get("children"))) children.add(plan(child));
            int count = kind.equals("Join") ? 2 : Set.of("Filter", "Project", "GroupBy", "Sort").contains(kind) ? 1 : 0;
            if (children.size() != count || !(input.get("schema") instanceof List<?>))
                throw failure("OPTIMIZER_INVALID_PLAN", "计划 children 或 schema 无效", input);
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : input.entrySet()) result.put(entry.getKey(), entry.getKey().equals("children") ? children : rewrite(entry.getValue()));
            if (kind.equals("Filter")) {
                Map<String, Object> predicate = map(result.get("predicate"));
                if (predicate == null) throw failure("OPTIMIZER_INVALID_PLAN", "Filter 必须包含 predicate", input);
                if (predicate.get("kind").equals("LiteralExpr") && Boolean.TRUE.equals(predicate.get("value"))
                        && "BOOL".equals(predicate.get("inferredType"))) {
                    if (!result.get("schema").equals(children.get(0).get("schema")))
                        throw failure("OPTIMIZER_INVALID_PLAN", "Filter schema 与子节点不一致", input);
                    rules.add(Optimizer.REMOVE_TRUE_FILTER); return children.get(0);
                }
            }
            return result;
        }
        Object rewrite(Object value) {
            if (value instanceof List<?> list) {
                List<Object> result = new ArrayList<>(); for (Object item : list) result.add(rewrite(item)); return result;
            }
            if (!(value instanceof Map<?, ?>)) return value;
            Map<String, Object> input = map(value), result = new LinkedHashMap<>();
            for (var entry : input.entrySet()) result.put(entry.getKey(), rewrite(entry.getValue()));
            if (input.get("kind") instanceof String kind && kind.endsWith("Expr")) {
                if (!Set.of("LiteralExpr", "NullLiteralExpr", "IdentifierExpr", "SlotRefExpr", "BinaryExpr", "UnaryExpr", "IsNullExpr", "AggregateExpr", "StarExpr").contains(kind))
                    throw failure("OPTIMIZER_UNSUPPORTED_EXPRESSION", "不支持该表达式节点：" + kind, input);
                if ("BOOL".equals(result.get("inferredType")) && compatible(result)) {
                    // 原优化器公开的 Delete 计划入口可以优化 BOOL predicate，不需要 Catalog 或数据行。
                    Map<String, Object> wrapper = Map.of("kind", "Delete", "table", "_optimizer_expression",
                            "predicate", result, "children", List.of(), "schema", List.of());
                    OptimizeResponse response = optimizer.optimize(new OptimizeRequest(wrapper));
                    if (!response.ok()) throw new OptimizationFailure(response.error());
                    rules.addAll(response.appliedRules()); return map(response.optimizedPlan().get("predicate"));
                }
            }
            return result;
        }
        boolean compatible(Map<String, Object> expression) {
            if (!Set.of("INT", "VARCHAR", "BOOL").contains(expression.get("inferredType"))) return false;
            return switch (text(expression, "kind")) {
                case "LiteralExpr", "IdentifierExpr" -> true;
                case "UnaryExpr" -> "NOT".equals(expression.get("operator")) && compatible(map(expression.get("operand")));
                case "BinaryExpr" -> {
                    Map<String, Object> left = map(expression.get("left")), right = map(expression.get("right"));
                    String operator = text(expression, "operator");
                    boolean allowed = Set.of("AND", "OR").contains(operator)
                            || Set.of("=", "!=", "<", "<=", ">", ">=").contains(operator)
                            && Set.of("INT", "VARCHAR").contains(left.get("inferredType"))
                            && left.get("inferredType").equals(right.get("inferredType"));
                    yield allowed && compatible(left) && compatible(right);
                }
                default -> false;
            };
        }
    }
    private static OptimizationFailure failure(String code, String message, Map<String, Object> owner) {
        Map<String, Object> loc = owner == null ? null : map(owner.get("loc"));
        Integer line = loc != null && loc.get("line") instanceof Number n ? n.intValue() : null;
        Integer column = loc != null && loc.get("column") instanceof Number n ? n.intValue() : null;
        return new OptimizationFailure(new OptimizerError(code, message, line, column));
    }
    private static final class OptimizationFailure extends RuntimeException {
        final OptimizerError error;
        OptimizationFailure(OptimizerError error) { super(error.message()); this.error = error; }
    }
}
