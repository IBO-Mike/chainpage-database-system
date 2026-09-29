package com.chainpage.sqlcompiler.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将语义已验证的 AST 转为逻辑计划，不查询 Catalog 或重新推导类型。 */
public final class PlanGenerator {
    /** 按输入顺序生成计划；任一语句失败时只返回错误，不暴露此前的部分计划。 */
    public BuildPlanResponse buildPlan(BuildPlanRequest request) {
        try {
            if (request == null || request.statements() == null)
                throw fail("PLANNER_INVALID_REQUEST", "请求必须包含 statements 数组", null);
            List<Map<String, Object>> plans = new ArrayList<>();
            for (Map<String, Object> statement : request.statements()) {
                if (statement == null)
                    throw fail("PLANNER_INVALID_REQUEST", "语句不得为 null", null);
                plans.add(buildStatement(statement));
            }
            return BuildPlanResponse.success(plans);
        } catch (PlanFailure failure) {
            return BuildPlanResponse.failure(failure.error);
        }
    }

    private Map<String, Object> buildStatement(Map<String, Object> statement) {
        String kind = text(statement, "kind");
        String table = normalize(text(statement, "table"));
        // 写操作直接携带目标表和操作参数，因此没有子计划，schema 也不表示查询结果列。
        return switch (kind) {
            case "CreateTableStmt" -> {
                Map<String, Object> plan = node("CreateTable", List.of(), List.of());
                plan.put("table", table);
                plan.put("columns", schema(statement.get("columns"), statement));
                yield plan;
            }
            case "InsertStmt" -> {
                Map<String, Object> plan = node("Insert", List.of(), List.of());
                plan.put("table", table);
                plan.put("columns", names(statement.get("columns"), statement));
                List<Map<String, Object>> values = new ArrayList<>();
                for (Object value : list(statement.get("values"), statement))
                    values.add(expression(value, statement));
                plan.put("values", values);
                yield plan;
            }
            case "SelectStmt" -> buildSelect(statement, table);
            case "DeleteStmt" -> {
                Map<String, Object> plan = node("Delete", List.of(), List.of());
                plan.put("table", table);
                plan.put("predicate", predicate(statement));
                yield plan;
            }
            default -> throw fail("PLANNER_UNSUPPORTED_STATEMENT", "不支持的语句：" + kind, statement);
        };
    }

    private Map<String, Object> buildSelect(Map<String, Object> statement, String table) {
        // 使用语义阶段冻结的表结构，才能识别同批次 CREATE 的临时表而不访问持久化目录。
        if (!(statement.get("resolvedTable") instanceof Map<?, ?>))
            throw fail("PLANNER_MISSING_SCHEMA", "SELECT 缺少语义阶段的 resolvedTable 标注", statement);
        Map<String, Object> resolved = object(statement.get("resolvedTable"), statement);
        if (!normalize(text(resolved, "name")).equals(table))
            throw fail("PLANNER_INVALID_AST", "resolvedTable 与查询表不一致", statement);
        List<Map<String, Object>> scanSchema = schema(resolved.get("columns"), statement);
        // 从叶子向根包装：SeqScan -> 可选 Filter -> Project；父节点消费子节点的输出。
        // Filter 保留完整行结构，使 WHERE 可以引用未出现在 SELECT 列表中的列。
        Map<String, Object> input = node("SeqScan", List.of(), scanSchema);
        input.put("table", table);
        Map<String, Object> predicate = predicate(statement);
        if (predicate != null) {
            Map<String, Object> filter = node("Filter", List.of(input), scanSchema);
            filter.put("predicate", predicate);
            input = filter;
        }
        List<String> columns = names(statement.get("columns"), statement);
        // 星号按表结构中的列顺序展开；显式列列表则保留用户顺序及重复列。
        if (columns.equals(List.of("*")))
            columns = scanSchema.stream().map(column -> (String) column.get("name")).toList();
        List<Map<String, Object>> projected = new ArrayList<>();
        for (String name : columns) {
            Map<String, Object> column = scanSchema.stream()
                    .filter(item -> item.get("name").equals(name)).findFirst()
                    .orElseThrow(() -> fail("PLANNER_INVALID_AST", "resolvedTable 缺少投影列：" + name, statement));
            projected.add(column);
        }
        Map<String, Object> project = node("Project", List.of(input), projected);
        project.put("columns", columns);
        return project;
    }

    private Map<String, Object> predicate(Map<String, Object> statement) {
        // 显式 null 表示没有 WHERE；缺字段表示 AST 不完整，不能等同于无条件操作。
        if (!statement.containsKey("where"))
            throw fail("PLANNER_INVALID_AST", "语句缺少 where 字段", statement);
        return statement.get("where") == null ? null : expression(statement.get("where"), statement);
    }

    // 只检查计划所依赖的标注和结构，不重复执行语义类型检查。
    private Map<String, Object> expression(Object value, Map<String, Object> owner) {
        Map<String, Object> expr = object(value, owner);
        validateExpression(expr);
        // 连同 binding、inferredType 和 loc 一起复制，后续优化不会改写原始语义 AST。
        return object(deepCopy(expr), expr);
    }

    private void validateExpression(Map<String, Object> expr) {
        if (!(expr.get("inferredType") instanceof String type)
                || !Set.of("INT", "VARCHAR", "BOOL").contains(type))
            throw fail("PLANNER_MISSING_ANNOTATION", "表达式缺少有效 inferredType", expr);
        switch (text(expr, "kind")) {
            case "IdentifierExpr" -> {
                Map<String, Object> binding = object(expr.get("binding"), expr);
                text(binding, "table");
                text(binding, "column");
                text(binding, "dataType");
            }
            case "LiteralExpr" -> {
                text(expr, "literalType");
                if (!expr.containsKey("value"))
                    throw fail("PLANNER_INVALID_AST", "字面量缺少 value", expr);
            }
            case "BinaryExpr" -> {
                text(expr, "operator");
                validateExpression(object(expr.get("left"), expr));
                validateExpression(object(expr.get("right"), expr));
            }
            case "UnaryExpr" -> {
                text(expr, "operator");
                validateExpression(object(expr.get("operand"), expr));
            }
            default -> throw fail("PLANNER_INVALID_AST", "未知表达式节点", expr);
        }
    }

    private List<Map<String, Object>> schema(Object value, Map<String, Object> owner) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list(value, owner)) {
            Map<String, Object> column = object(item, owner);
            String type = text(column, "dataType");
            if (!Set.of("INT", "VARCHAR").contains(type))
                throw fail("PLANNER_INVALID_AST", "schema 类型必须是 INT 或 VARCHAR", owner);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("name", normalize(text(column, "name")));
            output.put("dataType", type);
            result.add(output);
        }
        if (result.isEmpty()) throw fail("PLANNER_INVALID_AST", "表结构不得为空", owner);
        return result;
    }

    private List<String> names(Object value, Map<String, Object> owner) {
        List<String> result = new ArrayList<>();
        for (Object item : list(value, owner)) {
            if (!(item instanceof String name) || name.isEmpty())
                throw fail("PLANNER_INVALID_AST", "列名必须是非空字符串", owner);
            result.add(normalize(name));
        }
        if (result.isEmpty()) throw fail("PLANNER_INVALID_AST", "列集合不得为空", owner);
        return result;
    }

    private static Map<String, Object> node(String kind, List<Map<String, Object>> children,
                                             List<Map<String, Object>> schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("children", children);
        // 各算子独立持有 schema，避免调整一个节点的列信息时影响其他节点。
        result.put("schema", deepCopy(schema));
        return result;
    }

    private static String normalize(String name) { return name.toLowerCase(Locale.ROOT); }

    private static String text(Map<String, Object> node, String field) {
        if (!(node.get(field) instanceof String text) || text.isEmpty())
            throw fail("PLANNER_INVALID_AST", "字段必须为非空字符串：" + field, node);
        return text;
    }

    private static List<?> list(Object value, Map<String, Object> owner) {
        if (!(value instanceof List<?> result))
            throw fail("PLANNER_INVALID_AST", "字段必须为数组", owner);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, Map<String, Object> owner) {
        if (!(value instanceof Map<?, ?> result))
            throw fail("PLANNER_INVALID_AST", "字段必须为对象", owner);
        return (Map<String, Object>) result;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
                result.put((String) entry.getKey(), deepCopy(entry.getValue()));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(deepCopy(item));
            return result;
        }
        return value;
    }

    private static PlanFailure fail(String code, String message, Map<String, Object> node) {
        Integer line = null, column = null;
        if (node != null && node.get("loc") instanceof Map<?, ?> loc) {
            if (loc.get("line") instanceof Number number) line = number.intValue();
            if (loc.get("column") instanceof Number number) column = number.intValue();
        }
        return new PlanFailure(new PlannerError("PLANNER", code, message, line, column, List.of()));
    }

    private static final class PlanFailure extends RuntimeException {
        private final PlannerError error;
        private PlanFailure(PlannerError error) { super(error.message()); this.error = error; }
    }
}
