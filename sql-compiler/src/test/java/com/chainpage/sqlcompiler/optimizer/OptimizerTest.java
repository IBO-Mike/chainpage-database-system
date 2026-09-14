package com.chainpage.sqlcompiler.optimizer;

import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.ast.JsonCodec;
import com.chainpage.sqlcompiler.catalog.CatalogRequest;
import com.chainpage.sqlcompiler.catalog.ColumnSchema;
import com.chainpage.sqlcompiler.catalog.InMemoryCatalog;
import com.chainpage.sqlcompiler.planner.BuildPlanResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class OptimizerTest {
    private static int checks;
    private static final List<Map<String, Object>> SCHEMA = List.of(
            Map.of("name", "id", "dataType", "INT"), Map.of("name", "name", "dataType", "VARCHAR"));

    public static void main(String[] args) {
        foldsConstantsAndRemovesTrueFilters();
        simplifiesBooleanIdentities();
        preservesWritePlansAndUnchangedPlans();
        preservesInputsAndAnnotations();
        returnsStructuredErrors();
        preservesResultsAndIsIdempotent();
        System.out.println("Optimizer tests passed: " + checks);
    }

    private static void foldsConstantsAndRemovesTrueFilters() {
        for (String operator : List.of("=", "!=", ">", ">=", "<", "<=")) {
            for (long left : List.of(-1L, 0L, Long.MAX_VALUE)) {
                long right = left == Long.MAX_VALUE ? Long.MAX_VALUE - 1 : 0;
                assertConstant(binary(operator, literal(left, "INT"), literal(right, "INT")),
                        comparison(operator, Long.compare(left, right)));
            }
            for (String left : List.of("", "Tom's", "a", "中")) {
                assertConstant(binary(operator, literal(left, "VARCHAR"), literal("a", "VARCHAR")),
                        comparison(operator, left.compareTo("a")));
            }
        }
        assertConstant(binary("=", literal(1, "INT"), literal(1L, "INT")), true);
        for (boolean left : List.of(false, true)) {
            assertConstant(not(literal(left, "BOOL")), !left);
            for (boolean right : List.of(false, true)) {
                assertConstant(binary("AND", literal(left, "BOOL"), literal(right, "BOOL")), left && right);
                assertConstant(binary("OR", literal(left, "BOOL"), literal(right, "BOOL")), left || right);
            }
        }
        OptimizeResponse response = optimize(sql("SELECT name FROM t WHERE (1=1 OR id>0) AND NOT (2<1);"));
        check(child(response.optimizedPlan()).get("kind").equals("SeqScan"), "recursive fold removes filter");
        check(response.appliedRules().equals(List.of(Optimizer.CONSTANT_FOLDING,
                Optimizer.BOOLEAN_SIMPLIFICATION, Optimizer.REMOVE_TRUE_FILTER)), "stable rule order without duplicates");
        check(response.optimizedPlan().get("schema").equals(List.of(SCHEMA.get(1))), "projection schema preserved");

        Map<String, Object> nested = filter(literal(true, "BOOL"), filter(literal(true, "BOOL"), scan()));
        check(optimize(nested).optimizedPlan().get("kind").equals("SeqScan"), "nested filters removed bottom up");
    }

    private static void simplifiesBooleanIdentities() {
        Map<String, Object> p = binary(">", identifier(), literal(0L, "INT"));
        for (String operator : List.of("AND", "OR")) {
            for (boolean constant : List.of(false, true)) {
                for (boolean constantOnLeft : List.of(false, true)) {
                    Map<String, Object> c = literal(constant, "BOOL");
                    Map<String, Object> expression = constantOnLeft ? binary(operator, c, p) : binary(operator, p, c);
                    OptimizeResponse response = optimize(delete(expression));
                    Object result = response.optimizedPlan().get("predicate");
                    boolean identity = operator.equals("AND") == constant;
                    check(identity ? result.equals(p) : map(result).get("value").equals(constant),
                            "boolean identity or annihilator: " + operator + " " + constant + " " + constantOnLeft);
                    check(response.appliedRules().equals(List.of(Optimizer.BOOLEAN_SIMPLIFICATION)), "identity rule recorded");
                }
            }
        }
        OptimizeResponse response = optimize(delete(not(not(p))));
        check(response.optimizedPlan().get("predicate").equals(p), "double NOT eliminated");
        check(response.appliedRules().equals(List.of(Optimizer.BOOLEAN_SIMPLIFICATION)), "double NOT rule recorded");
        Map<String, Object> falsePlan = optimize(sql("SELECT * FROM t WHERE 1=2;")).optimizedPlan();
        check(child(falsePlan).get("kind").equals("Filter"), "false filter retained");
        check(map(child(falsePlan).get("predicate")).get("value").equals(false), "false predicate folded");
    }

    private static void preservesWritePlansAndUnchangedPlans() {
        for (String text : List.of("CREATE TABLE u(id INT);", "INSERT INTO t(id,name) VALUES(1,'Tom');",
                "SELECT name,id,name FROM t;", "SELECT * FROM t WHERE id>0;", "DELETE FROM t;")) {
            Map<String, Object> input = sql(text);
            OptimizeResponse response = optimize(input);
            check(response.optimizedPlan().equals(input), "unchanged plan: " + text);
            check(response.appliedRules().isEmpty(), "no spurious rule: " + text);
        }
        OptimizeResponse deletion = optimize(sql("DELETE FROM t WHERE 1=1;"));
        check(deletion.optimizedPlan().get("kind").equals("Delete"), "true delete is never removed");
        check(map(deletion.optimizedPlan().get("predicate")).get("value").equals(true), "delete predicate folded");
        check(!deletion.appliedRules().contains(Optimizer.REMOVE_TRUE_FILTER), "delete does not report filter removal");

        Map<String, Object> insert = new LinkedHashMap<>(sql("INSERT INTO t(id) VALUES(1);"));
        // 直接计划入口也递归遍历 values；基本 SQL INSERT 文法仍只接受 INT/VARCHAR 字面量。
        insert.put("values", List.of(binary("=", literal(1L, "INT"), literal(1L, "INT"))));
        OptimizeResponse response = optimize(insert);
        check(map(((List<?>) response.optimizedPlan().get("values")).get(0)).get("value").equals(true),
                "insert values visited recursively");
    }

    private static void preservesInputsAndAnnotations() {
        Map<String, Object> input = sql("SELECT name FROM t WHERE 1=1 AND id>0;");
        String before = JsonCodec.stringify(input);
        Map<String, Object> expectedPredicate = map(map(child(input).get("predicate")).get("right"));
        OptimizeResponse response = optimize(input);
        check(JsonCodec.stringify(input).equals(before), "optimization does not mutate input");
        check(response.originalPlan().equals(input), "originalPlan is exact snapshot");
        Map<String, Object> resultPredicate = map(child(response.optimizedPlan()).get("predicate"));
        check(resultPredicate.equals(expectedPredicate), "retained expression preserves loc, binding and inferredType");
        map(map(resultPredicate.get("left")).get("binding")).put("column", "changed");
        map(((List<?>) child(child(response.optimizedPlan())).get("schema")).get(0)).put("name", "changed");
        check(JsonCodec.stringify(input).equals(before), "optimized nested maps are independent of input");
        check(JsonCodec.stringify(response.originalPlan()).equals(before), "optimized nested maps are independent of originalPlan");
        child(response.originalPlan()).put("extra", "original only");
        check(JsonCodec.stringify(input).equals(before), "original snapshot independent of input");
        input.put("extra", "input only");
        check(!response.originalPlan().containsKey("extra") && !response.optimizedPlan().containsKey("extra"),
                "later input mutation cannot change outputs");

        Map<String, Object> constant = binary("=", literal(1L, "INT"), literal(1L, "INT"));
        constant.put("loc", Map.of("line", 3, "column", 7));
        Map<String, Object> folded = map(optimize(delete(constant)).optimizedPlan().get("predicate"));
        check(folded.get("loc").equals(constant.get("loc")), "folded literal inherits expression location");
        check(folded.get("literalType").equals("BOOL") && folded.get("inferredType").equals("BOOL"), "folded literal typed");
        check(!folded.containsKey("operator") && !folded.containsKey("left") && !folded.containsKey("right"),
                "folded literal has no stale operator fields");
        Map<String, Object> envelope = optimize(delete(constant)).toMap();
        check(envelope.get("ok").equals(true) && !envelope.containsKey("error"), "success envelope");
        check(map(envelope.get("data")).keySet().equals(java.util.Set.of("originalPlan", "optimizedPlan", "appliedRules")),
                "exact success data fields");
        check(JsonCodec.parse(JsonCodec.stringify(envelope)) instanceof Map<?, ?>, "response is JSON compatible");
    }

    private static void returnsStructuredErrors() {
        Optimizer optimizer = new Optimizer();
        assertError(optimizer.optimize(null), "OPTIMIZER_INVALID_REQUEST");
        assertError(optimizer.optimize(new OptimizeRequest(null)), "OPTIMIZER_INVALID_REQUEST");
        assertError(optimizer.optimize(new OptimizeRequest(Map.of())), "OPTIMIZER_INVALID_PLAN");
        Map<String, Object> bad = scan();
        bad.put("kind", "Join");
        assertError(optimizer.optimize(new OptimizeRequest(bad)), "OPTIMIZER_INVALID_PLAN");
        bad.put("kind", "Unknown");
        assertError(optimizer.optimize(new OptimizeRequest(bad)), "OPTIMIZER_UNSUPPORTED_PLAN");
        for (String field : List.of("children", "schema", "table")) {
            bad = scan();
            bad.remove(field);
            assertError(optimizer.optimize(new OptimizeRequest(bad)), "OPTIMIZER_INVALID_PLAN");
        }
        bad = filter(literal(true, "BOOL"), scan());
        bad.put("children", List.of());
        assertError(optimizer.optimize(new OptimizeRequest(bad)), "OPTIMIZER_INVALID_PLAN");
        bad = filter(literal(true, "BOOL"), scan());
        bad.put("schema", List.of());
        assertError(optimizer.optimize(new OptimizeRequest(bad)), "OPTIMIZER_INVALID_PLAN");
        bad = filter(null, scan());
        assertError(optimizer.optimize(new OptimizeRequest(bad)), "OPTIMIZER_INVALID_PLAN");
        for (Object value : List.of("1", 1.5, Double.NaN, Double.POSITIVE_INFINITY))
            assertError(optimizer.optimize(new OptimizeRequest(delete(binary("=", literal(value, "INT"), literal(1, "INT"))))),
                    "OPTIMIZER_INVALID_PLAN");
        bad = binary("=", literal(1, "INT"), literal("1", "VARCHAR"));
        assertError(optimizer.optimize(new OptimizeRequest(delete(bad))), "OPTIMIZER_INVALID_PLAN");
        bad = literal(true, "BOOL");
        bad.remove("inferredType");
        assertError(optimizer.optimize(new OptimizeRequest(delete(bad))), "OPTIMIZER_INVALID_PLAN");
        bad = binary("%", literal(1, "INT"), literal(2, "INT"));
        bad.put("loc", Map.of("line", 4, "column", 9));
        String before = JsonCodec.stringify(bad);
        OptimizeResponse failure = optimizer.optimize(new OptimizeRequest(delete(bad)));
        assertError(failure, "OPTIMIZER_UNSUPPORTED_EXPRESSION");
        check(failure.error().line() == 4 && failure.error().column() == 9, "error source position");
        check(JsonCodec.stringify(bad).equals(before), "failure does not mutate input");
        Map<String, Object> hidden = binary("OR", literal(true, "BOOL"), bad);
        assertError(optimizer.optimize(new OptimizeRequest(delete(hidden))), "OPTIMIZER_UNSUPPORTED_EXPRESSION");
        check(optimize(scan()).appliedRules().isEmpty(), "rules do not leak across calls");
    }

    private static void preservesResultsAndIsIdempotent() {
        List<Map<String, Object>> rows = List.of(Map.of("id", -1L, "name", "Amy"), Map.of("id", 0L, "name", "Tom"),
                Map.of("id", 1L, "name", "Tom"), Map.of("id", 1L, "name", "Tom"), Map.of("id", 2L, "name", "Zoe"));
        Random random = new Random(7);
        Optimizer optimizer = new Optimizer();
        for (int i = 0; i < 250; i++) {
            Map<String, Object> input = new LinkedHashMap<>(Map.of("kind", "Project", "columns", List.of("name"),
                    "schema", List.of(SCHEMA.get(1)), "children", List.of(filter(randomExpression(random, 4), scan()))));
            OptimizeResponse response = optimizer.optimize(new OptimizeRequest(input));
            check(response.ok(), "generated plan optimizes");
            check(execute(input, rows).equals(execute(response.optimizedPlan(), rows)), "same rows, order and duplicates");
            OptimizeResponse twice = optimizer.optimize(new OptimizeRequest(response.optimizedPlan()));
            check(twice.ok() && twice.optimizedPlan().equals(response.optimizedPlan()), "optimization is idempotent");
            check(twice.appliedRules().isEmpty(), "second pass applies no rules");
        }
    }

    private static Map<String, Object> randomExpression(Random random, int depth) {
        if (depth == 0) return switch (random.nextInt(4)) {
            case 0 -> literal(random.nextBoolean(), "BOOL");
            case 1 -> binary(">", identifier(), literal(0L, "INT"));
            case 2 -> binary("=", literal(1L, "INT"), literal((long) random.nextInt(3), "INT"));
            default -> binary("!=", literal("a", "VARCHAR"), literal("b", "VARCHAR"));
        };
        return random.nextInt(3) == 0 ? not(randomExpression(random, depth - 1))
                : binary(random.nextBoolean() ? "AND" : "OR", randomExpression(random, depth - 1), randomExpression(random, depth - 1));
    }

    // 小型测试解释器：实际比较优化前后结果，独立于优化器的重写逻辑。
    private static List<Map<String, Object>> execute(Map<String, Object> plan, List<Map<String, Object>> rows) {
        return switch ((String) plan.get("kind")) {
            case "SeqScan" -> rows;
            case "Filter" -> execute(child(plan), rows).stream()
                    .filter(row -> (Boolean) evaluate(map(plan.get("predicate")), row)).toList();
            case "Project" -> execute(child(plan), rows).stream().map(row -> {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Object column : (List<?>) plan.get("columns")) result.put((String) column, row.get(column));
                return result;
            }).toList();
            default -> throw new AssertionError("unsupported test plan");
        };
    }

    private static Object evaluate(Map<String, Object> expr, Map<String, Object> row) {
        return switch ((String) expr.get("kind")) {
            case "LiteralExpr" -> expr.get("value");
            case "IdentifierExpr" -> row.get(map(expr.get("binding")).get("column"));
            case "UnaryExpr" -> !(Boolean) evaluate(map(expr.get("operand")), row);
            case "BinaryExpr" -> {
                Object left = evaluate(map(expr.get("left")), row), right = evaluate(map(expr.get("right")), row);
                String op = (String) expr.get("operator");
                yield switch (op) {
                    case "AND" -> (Boolean) left && (Boolean) right;
                    case "OR" -> (Boolean) left || (Boolean) right;
                    default -> comparison(op, left instanceof Number
                            ? Long.compare(((Number) left).longValue(), ((Number) right).longValue())
                            : ((String) left).compareTo((String) right));
                };
            }
            default -> throw new AssertionError("unsupported test expression");
        };
    }

    private static boolean comparison(String operator, int result) {
        return switch (operator) {
            case "=" -> result == 0;
            case "!=" -> result != 0;
            case ">" -> result > 0;
            case ">=" -> result >= 0;
            case "<" -> result < 0;
            case "<=" -> result <= 0;
            default -> throw new AssertionError(operator);
        };
    }

    private static void assertConstant(Map<String, Object> expression, boolean expected) {
        OptimizeResponse response = optimize(filter(expression, scan()));
        check(response.appliedRules().contains(Optimizer.CONSTANT_FOLDING), "constant folding recorded");
        if (expected) check(response.optimizedPlan().get("kind").equals("SeqScan"), "true filter removed");
        else check(response.optimizedPlan().get("kind").equals("Filter")
                && map(response.optimizedPlan().get("predicate")).get("value").equals(false), "false filter remains");
    }

    private static void assertError(OptimizeResponse response, String code) {
        check(!response.ok() && response.error().code().equals(code), "error code: " + code);
        Map<String, Object> envelope = response.toMap(), error = map(envelope.get("error"));
        check(envelope.get("ok").equals(false) && !envelope.containsKey("data"), "failure envelope");
        check(error.get("stage").equals("OPTIMIZER") && error.get("expected").equals(List.of())
                && error.containsKey("line") && error.containsKey("column"), "uniform error fields");
    }

    private static OptimizeResponse optimize(Map<String, Object> plan) {
        OptimizeResponse response = new Optimizer().optimize(new OptimizeRequest(plan));
        check(response.ok(), "optimization succeeds: " + (response.error() == null ? "" : response.error().message()));
        return response;
    }

    private static Map<String, Object> sql(String sql) {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.execute(CatalogRequest.createTable("t", List.of(new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"))));
        BuildPlanResponse response = new CompilerFrontEnd().buildPlanSql(sql, catalog);
        check(response.ok(), "SQL compiles: " + sql);
        return response.plans().get(0);
    }

    private static Map<String, Object> literal(Object value, String type) {
        return new LinkedHashMap<>(Map.of("kind", "LiteralExpr", "literalType", type, "value", value,
                "inferredType", type, "loc", Map.of("line", 1, "column", 1)));
    }

    private static Map<String, Object> identifier() {
        return new LinkedHashMap<>(Map.of("kind", "IdentifierExpr", "name", "id", "inferredType", "INT",
                "binding", Map.of("table", "t", "column", "id", "dataType", "INT")));
    }

    private static Map<String, Object> binary(String operator, Map<String, Object> left, Map<String, Object> right) {
        return new LinkedHashMap<>(Map.of("kind", "BinaryExpr", "operator", operator,
                "left", left, "right", right, "inferredType", "BOOL"));
    }

    private static Map<String, Object> not(Map<String, Object> operand) {
        return new LinkedHashMap<>(Map.of("kind", "UnaryExpr", "operator", "NOT", "operand", operand, "inferredType", "BOOL"));
    }

    private static Map<String, Object> scan() {
        return new LinkedHashMap<>(Map.of("kind", "SeqScan", "table", "t", "children", List.of(), "schema", SCHEMA));
    }

    private static Map<String, Object> filter(Map<String, Object> predicate, Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>(Map.of("kind", "Filter", "children", List.of(input), "schema", SCHEMA));
        result.put("predicate", predicate);
        return result;
    }

    private static Map<String, Object> delete(Map<String, Object> predicate) {
        return new LinkedHashMap<>(Map.of("kind", "Delete", "table", "t", "predicate", predicate, "children", List.of(), "schema", List.of()));
    }

    private static Map<String, Object> child(Map<String, Object> node) { return map(((List<?>) node.get("children")).get(0)); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
