package com.chainpage.sqlcompiler.optimizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 第 7 部分：只处理计划，不访问 Catalog、存储或执行引擎。 */
public final class Optimizer {
    public static final String CONSTANT_FOLDING = "CONSTANT_FOLDING";
    public static final String BOOLEAN_SIMPLIFICATION = "BOOLEAN_SIMPLIFICATION";
    public static final String REMOVE_TRUE_FILTER = "REMOVE_TRUE_FILTER";
    private static final Set<String> COMPARISONS = Set.of("=", "!=", ">", ">=", "<", "<=");
    private static final Set<String> TYPES = Set.of("INT", "VARCHAR", "BOOL");

    public OptimizeResponse optimize(OptimizeRequest request) {
        try {
            if (request == null || request.plan() == null)
                throw fail("OPTIMIZER_INVALID_REQUEST", "请求必须包含 plan 对象", null);
            if (extended(request.plan())) return new ExtendedOptimizer().optimize(request.plan());
            Map<String, Object> original = object(copy(request.plan()), null);
            Session session = new Session();
            Rewrite result = session.plan(object(copy(original), null));
            return OptimizeResponse.success(original, result.node(), new ArrayList<>(session.rules));
        } catch (OptimizationFailure failure) {
            return OptimizeResponse.failure(failure.error);
        }
    }

    private static boolean extended(Object value) {
        if (value instanceof Map<?, ?> m) {
            if (Set.of("Update", "Sort", "GroupBy", "Join", "NullLiteralExpr", "IsNullExpr").contains((m.get("kind") == null ? "" : m.get("kind")))) return true;
            Object type = m.containsKey("dataType") ? m.get("dataType") : m.get("inferredType");
            if (type instanceof String && !Set.of("INT", "VARCHAR", "BOOL").contains(type)) return true;
            if ("BOOL".equals(m.get("dataType"))) return true;
            if ("UnaryExpr".equals(m.get("kind")) && !"NOT".equals(m.get("operator"))) return true;
            if ("BinaryExpr".equals(m.get("kind")) && Set.of("+", "-", "*", "/").contains(m.get("operator"))) return true;
            for (Object v : m.values()) if (extended(v)) return true;
        } else if (value instanceof List<?> l) for (Object v : l) if (extended(v)) return true;
        return false;
    }

    private record Rewrite(Map<String, Object> node, boolean changed) {}

    // 每次调用独立保存规则记录，避免跨请求残留；后序遍历让子表达式先折叠。
    private static final class Session {
        private final Set<String> rules = new LinkedHashSet<>();

        private Rewrite plan(Map<String, Object> input) {
            String kind = text(input, "kind");
            if (!Set.of("CreateTable", "Insert", "SeqScan", "Filter", "Project", "Delete").contains(kind))
                throw fail("OPTIMIZER_UNSUPPORTED_PLAN", "不支持的计划节点：" + kind, input);
            List<?> rawChildren = list(input.get("children"), input);
            int childCount = Set.of("Filter", "Project").contains(kind) ? 1 : 0;
            if (rawChildren.size() != childCount)
                throw invalid(kind + " 的 children 数量必须为 " + childCount, input);
            validateSchema(input.get("schema"), input);
            Map<String, Object> node = new LinkedHashMap<>(input);
            List<Map<String, Object>> children = new ArrayList<>();
            boolean changed = false;
            for (Object child : rawChildren) {
                Rewrite result = plan(object(child, input));
                children.add(result.node());
                changed |= result.changed();
            }
            node.put("children", children);
            if (!Set.of("Filter", "Project").contains(kind)) text(node, "table");
            switch (kind) {
                case "CreateTable" -> validateSchema(node.get("columns"), node);
                case "Project" -> validateNames(node.get("columns"), node);
                case "Insert" -> {
                    validateNames(node.get("columns"), node);
                    List<Map<String, Object>> values = new ArrayList<>();
                    for (Object value : list(node.get("values"), node)) {
                        Rewrite result = expression(object(value, node));
                        values.add(result.node());
                        changed |= result.changed();
                    }
                    if (values.size() != list(node.get("columns"), node).size())
                        throw invalid("Insert 的 columns 与 values 数量必须一致", node);
                    node.put("values", values);
                }
                case "Filter", "Delete" -> {
                    if (!node.containsKey("predicate")) throw invalid("缺少 predicate 字段", node);
                    if (node.get("predicate") != null || kind.equals("Filter")) {
                        Rewrite predicate = expression(object(node.get("predicate"), node));
                        requireType(predicate.node(), "BOOL", node);
                        node.put("predicate", predicate.node());
                        changed |= predicate.changed();
                        if (kind.equals("Filter")) {
                            if (!node.get("schema").equals(children.get(0).get("schema")))
                                throw invalid("Filter schema 必须与子节点一致", node);
                            if (Boolean.TRUE.equals(bool(predicate.node())))
                                return applied(children.get(0), REMOVE_TRUE_FILTER);
                        }
                    }
                }
                default -> { /* SeqScan 无表达式。 */ }
            }
            return new Rewrite(node, changed);
        }

        private Rewrite expression(Map<String, Object> input) {
            Map<String, Object> node = new LinkedHashMap<>(input);
            String type = text(node, "inferredType");
            if (!TYPES.contains(type)) throw invalid("无效的 inferredType", node);
            switch (text(node, "kind")) {
                case "LiteralExpr" -> {
                    if (!text(node, "literalType").equals(type))
                        throw invalid("literalType 与 inferredType 不一致", node);
                    Object value = node.get("value");
                    if (type.equals("INT")) integer(value, node);
                    else if (type.equals("VARCHAR") ? !(value instanceof String) : !(value instanceof Boolean))
                        throw invalid("字面量 value 与类型不一致", node);
                    return new Rewrite(node, false);
                }
                case "IdentifierExpr" -> {
                    text(node, "name");
                    Map<String, Object> binding = object(node.get("binding"), node);
                    text(binding, "table");
                    text(binding, "column");
                    if (!text(binding, "dataType").equals(type))
                        throw invalid("binding.dataType 与 inferredType 不一致", node);
                    return new Rewrite(node, false);
                }
                case "UnaryExpr" -> {
                    if (!text(node, "operator").equals("NOT"))
                        throw fail("OPTIMIZER_UNSUPPORTED_EXPRESSION", "只支持一元 NOT", node);
                    requireType(node, "BOOL", node);
                    Rewrite operand = expression(object(node.get("operand"), node));
                    requireType(operand.node(), "BOOL", node);
                    node.put("operand", operand.node());
                    Boolean value = bool(operand.node());
                    if (value != null) return applied(literal(node, !value), CONSTANT_FOLDING);
                    if (operand.node().get("kind").equals("UnaryExpr"))
                        return applied(object(operand.node().get("operand"), node), BOOLEAN_SIMPLIFICATION);
                    return new Rewrite(node, operand.changed());
                }
                case "BinaryExpr" -> {
                    String operator = text(node, "operator");
                    if (!COMPARISONS.contains(operator) && !Set.of("AND", "OR").contains(operator))
                        throw fail("OPTIMIZER_UNSUPPORTED_EXPRESSION", "不支持的运算符：" + operator, node);
                    requireType(node, "BOOL", node);
                    Rewrite left = expression(object(node.get("left"), node));
                    Rewrite right = expression(object(node.get("right"), node));
                    node.put("left", left.node());
                    node.put("right", right.node());
                    if (COMPARISONS.contains(operator)) {
                        String leftType = text(left.node(), "inferredType");
                        if (!Set.of("INT", "VARCHAR").contains(leftType)
                                || !leftType.equals(right.node().get("inferredType")))
                            throw invalid("比较两侧必须是相同的 INT 或 VARCHAR", node);
                        if (isLiteral(left.node()) && isLiteral(right.node())) {
                            int compared = leftType.equals("INT")
                                    ? integer(left.node().get("value"), node).compareTo(integer(right.node().get("value"), node))
                                    : ((String) left.node().get("value")).compareTo((String) right.node().get("value"));
                            boolean value = switch (operator) {
                                case "=" -> compared == 0;
                                case "!=" -> compared != 0;
                                case ">" -> compared > 0;
                                case ">=" -> compared >= 0;
                                case "<" -> compared < 0;
                                default -> compared <= 0;
                            };
                            return applied(literal(node, value), CONSTANT_FOLDING);
                        }
                    } else {
                        requireType(left.node(), "BOOL", node);
                        requireType(right.node(), "BOOL", node);
                        Boolean l = bool(left.node()), r = bool(right.node());
                        boolean and = operator.equals("AND");
                        if (l != null && r != null)
                            return applied(literal(node, and ? l && r : l || r), CONSTANT_FOLDING);
                        if (l != null)
                            return applied(l == and ? right.node() : literal(node, l), BOOLEAN_SIMPLIFICATION);
                        if (r != null)
                            return applied(r == and ? left.node() : literal(node, r), BOOLEAN_SIMPLIFICATION);
                    }
                    return new Rewrite(node, left.changed() || right.changed());
                }
                default -> throw fail("OPTIMIZER_UNSUPPORTED_EXPRESSION", "不支持的表达式节点", node);
            }
        }

        private Rewrite applied(Map<String, Object> node, String rule) {
            rules.add(rule);
            return new Rewrite(node, true);
        }
    }

    private static boolean isLiteral(Map<String, Object> node) { return "LiteralExpr".equals(node.get("kind")); }

    private static Boolean bool(Map<String, Object> node) {
        return isLiteral(node) && "BOOL".equals(node.get("inferredType")) ? (Boolean) node.get("value") : null;
    }

    private static Map<String, Object> literal(Map<String, Object> source, boolean value) {
        Map<String, Object> node = new LinkedHashMap<>(source);
        for (String field : List.of("operator", "left", "right", "operand", "binding", "name")) node.remove(field);
        node.put("kind", "LiteralExpr");
        node.put("literalType", "BOOL");
        node.put("inferredType", "BOOL");
        node.put("value", value);
        return node;
    }

    private static void requireType(Map<String, Object> expression, String type, Map<String, Object> owner) {
        if (!type.equals(expression.get("inferredType"))) throw invalid("表达式类型必须是 " + type, owner);
    }

    private static void validateSchema(Object value, Map<String, Object> owner) {
        for (Object item : list(value, owner)) {
            Map<String, Object> column = object(item, owner);
            text(column, "name");
            if (!Set.of("INT", "VARCHAR").contains(text(column, "dataType")))
                throw invalid("schema 类型必须为 INT 或 VARCHAR", owner);
        }
    }

    private static void validateNames(Object value, Map<String, Object> owner) {
        List<?> names = list(value, owner);
        if (names.isEmpty()) throw invalid("列集合不得为空", owner);
        for (Object name : names)
            if (!(name instanceof String text) || text.isEmpty()) throw invalid("列名必须为非空字符串", owner);
    }

    private static BigInteger integer(Object value, Map<String, Object> owner) {
        if (value instanceof Number number) {
            try { return new BigDecimal(number.toString()).toBigIntegerExact(); }
            catch (NumberFormatException | ArithmeticException ignored) { /* 统一返回结构化错误。 */ }
        }
        throw invalid("INT 字面量必须是有限整数", owner);
    }

    private static String text(Map<String, Object> node, String field) {
        if (!(node.get(field) instanceof String value) || value.isEmpty())
            throw invalid(field + " 必须为非空字符串", node);
        return value;
    }

    private static List<?> list(Object value, Map<String, Object> owner) {
        if (!(value instanceof List<?> result)) throw invalid("字段必须为数组", owner);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, Map<String, Object> owner) {
        if (!(value instanceof Map<?, ?> result)) throw invalid("字段必须为对象", owner);
        return (Map<String, Object>) result;
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid("对象键必须为字符串", null);
                result.put(key, copy(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(copy(item));
            return result;
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        throw invalid("计划必须由 JSON 兼容值组成", null);
    }

    private static OptimizationFailure invalid(String message, Map<String, Object> owner) {
        return fail("OPTIMIZER_INVALID_PLAN", message, owner);
    }

    private static OptimizationFailure fail(String code, String message, Map<String, Object> owner) {
        Integer line = null, column = null;
        if (owner != null && owner.get("loc") instanceof Map<?, ?> loc) {
            if (loc.get("line") instanceof Number number) line = number.intValue();
            if (loc.get("column") instanceof Number number) column = number.intValue();
        }
        return new OptimizationFailure(new OptimizerError(code, message, line, column));
    }

    private static final class OptimizationFailure extends RuntimeException {
        private final OptimizerError error;
        private OptimizationFailure(OptimizerError error) { super(error.message()); this.error = error; }
    }
}
