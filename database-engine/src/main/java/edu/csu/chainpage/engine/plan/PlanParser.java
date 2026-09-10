package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

// 把SQL编译器返回的JSON计划解析并校验为本模块计划对象
public final class PlanParser {

    private static final Set<String> SUPPORTED_KINDS = Set.of(
            "CreateTable",
            "Insert",
            "SeqScan",
            "Filter",
            "Project",
            "Delete"
    );

    // 解析一个原始JSON计划
    public DbResult<PlanNode> parse(Object rawPlan) {
        if (rawPlan instanceof PlanNode plan) {
            DbResult<Void> validation = validate(plan);
            return validation.isOk()
                    ? DbResult.ok(plan)
                    : DbResult.fail(validation.error());
        }
        if (!(rawPlan instanceof Map<?, ?> rawMap)) {
            return failure("EXECUTOR_INVALID_PLAN", "计划必须是JSON对象");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return failure("EXECUTOR_INVALID_PLAN", "计划字段名必须是字符串");
            }
            fields.put(key, entry.getValue());
        }
        Object rawKind = fields.get("kind");
        if (!(rawKind instanceof String kind) || kind.isBlank()) {
            return failure("EXECUTOR_INVALID_PLAN", "计划缺少有效kind字段");
        }
        if (!SUPPORTED_KINDS.contains(kind)) {
            return failure("EXECUTOR_UNSUPPORTED_PLAN", "不支持的计划节点：" + kind);
        }
        if (!fields.containsKey("children") || !fields.containsKey("schema")) {
            return failure("EXECUTOR_INVALID_PLAN", "计划必须包含children和schema字段");
        }

        DbResult<List<PlanNode>> children = parseChildren(fields.get("children"));
        if (!children.isOk()) {
            return DbResult.fail(children.error());
        }
        DbResult<List<ColumnSchema>> schema = parseSchema(fields.get("schema"));
        if (!schema.isOk()) {
            return DbResult.fail(schema.error());
        }

        final JsonPlanNode plan;
        try {
            plan = new JsonPlanNode(kind, fields, children.data(), schema.data());
        } catch (RuntimeException exception) {
            return failure("EXECUTOR_INVALID_PLAN", "计划字段包含无法保存的JSON值");
        }
        DbResult<Void> validation = validate(plan);
        if (!validation.isOk()) {
            return DbResult.fail(validation.error());
        }
        return DbResult.ok(plan);
    }

    // 校验已解析计划的节点种类、字段、子计划数量和输出模式
    public DbResult<Void> validate(PlanNode plan) {
        if (plan == null || plan.kind() == null || plan.kind().isBlank()) {
            return failure("EXECUTOR_INVALID_PLAN", "计划节点不能为空且必须包含kind");
        }
        if (!SUPPORTED_KINDS.contains(plan.kind())) {
            return failure("EXECUTOR_UNSUPPORTED_PLAN", "不支持的计划节点：" + plan.kind());
        }
        if (plan.children() == null || plan.schema() == null
                || plan.children().stream().anyMatch(Objects::isNull)
                || plan.schema().stream().anyMatch(Objects::isNull)) {
            return failure("EXECUTOR_INVALID_PLAN", "计划的children或schema不能包含null");
        }

        if (plan instanceof JsonPlanNode jsonPlan) {
            DbResult<Void> fieldValidation = validateFields(jsonPlan);
            if (!fieldValidation.isOk()) {
                return fieldValidation;
            }
        }

        int expectedChildren = switch (plan.kind()) {
            case "Filter", "Project" -> 1;
            default -> 0;
        };
        if (plan.children().size() != expectedChildren) {
            return failure(
                    "EXECUTOR_INVALID_PLAN",
                    plan.kind() + "节点需要" + expectedChildren + "个子计划"
            );
        }
        if ("CreateTable".equals(plan.kind())
                || "Insert".equals(plan.kind())
                || "Delete".equals(plan.kind())) {
            if (!plan.schema().isEmpty()) {
                return failure("EXECUTOR_INVALID_PLAN", plan.kind() + "节点的schema必须为空");
            }
        }
        return DbResult.ok(null);
    }

    // 解析一个子计划
    public DbResult<PlanNode> parseChild(Object rawChild) {
        return parse(rawChild);
    }

    // 解析children数组
    private DbResult<List<PlanNode>> parseChildren(Object rawChildren) {
        if (!(rawChildren instanceof List<?> list)) {
            return failure("EXECUTOR_INVALID_PLAN", "children必须是数组");
        }
        List<PlanNode> children = new ArrayList<>();
        for (Object rawChild : list) {
            DbResult<PlanNode> child = parseChild(rawChild);
            if (!child.isOk()) {
                return DbResult.fail(child.error());
            }
            children.add(child.data());
        }
        return DbResult.ok(List.copyOf(children));
    }

    // 解析schema数组
    private DbResult<List<ColumnSchema>> parseSchema(Object rawSchema) {
        if (!(rawSchema instanceof List<?> list)) {
            return failure("EXECUTOR_INVALID_PLAN", "schema必须是数组");
        }
        List<ColumnSchema> schema = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object rawColumn : list) {
            if (!(rawColumn instanceof Map<?, ?> map)) {
                return failure("EXECUTOR_INVALID_PLAN", "schema中的列必须是对象");
            }
            Object rawName = map.get("name");
            Object rawType = map.get("dataType");
            if (!(rawName instanceof String name) || name.isBlank()
                    || !(rawType instanceof String dataType) || dataType.isBlank()) {
                return failure("EXECUTOR_INVALID_PLAN", "列必须包含name和dataType");
            }
            if (!("INT".equalsIgnoreCase(dataType) || "VARCHAR".equalsIgnoreCase(dataType))) {
                return failure("EXECUTOR_INVALID_PLAN", "schema中的列类型不受支持");
            }
            String normalizedName = name.toLowerCase(Locale.ROOT);
            if (!names.add(normalizedName)) {
                return failure("EXECUTOR_INVALID_PLAN", "schema中不能包含重复列");
            }
            schema.add(new ColumnSchema(
                    normalizedName,
                    dataType.toUpperCase(Locale.ROOT)
            ));
        }
        return DbResult.ok(List.copyOf(schema));
    }

    // 校验不同计划节点的专属字段
    private DbResult<Void> validateFields(JsonPlanNode plan) {
        switch (plan.kind()) {
            case "CreateTable" -> {
                DbResult<Void> table = requireString(plan, "table");
                if (!table.isOk()) {
                    return table;
                }
                return validateColumnDefinitions(plan.field("columns"));
            }
            case "Insert" -> {
                DbResult<Void> table = requireString(plan, "table");
                if (!table.isOk()) {
                    return table;
                }
                DbResult<Void> columns = validateStringList(plan.field("columns"), "columns");
                if (!columns.isOk()) {
                    return columns;
                }
                return validateList(plan.field("values"), "values");
            }
            case "SeqScan" -> {
                return requireString(plan, "table");
            }
            case "Filter" -> {
                if (!plan.fields().containsKey("predicate") || plan.field("predicate") == null) {
                    return failure("EXECUTOR_INVALID_PLAN", "Filter节点必须包含predicate");
                }
                return DbResult.ok(null);
            }
            case "Project" -> {
                return validateStringList(plan.field("columns"), "columns");
            }
            case "Delete" -> {
                DbResult<Void> table = requireString(plan, "table");
                if (!table.isOk()) {
                    return table;
                }
                if (!plan.fields().containsKey("predicate")) {
                    return failure("EXECUTOR_INVALID_PLAN", "Delete节点必须包含predicate字段");
                }
                return DbResult.ok(null);
            }
            default -> {
                return failure("EXECUTOR_UNSUPPORTED_PLAN", "不支持的计划节点：" + plan.kind());
            }
        }
    }

    // 要求一个非空字符串字段
    private DbResult<Void> requireString(JsonPlanNode plan, String field) {
        Object value = plan.field(field);
        if (!(value instanceof String text) || text.isBlank()) {
            return failure("EXECUTOR_INVALID_PLAN", plan.kind() + "节点的" + field + "字段无效");
        }
        return DbResult.ok(null);
    }

    // 校验字符串数组
    private DbResult<Void> validateStringList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            return failure("EXECUTOR_INVALID_PLAN", field + "必须是数组");
        }
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                return failure("EXECUTOR_INVALID_PLAN", field + "只能包含非空字符串");
            }
        }
        return DbResult.ok(null);
    }

    // 校验任意数组字段
    private DbResult<Void> validateList(Object value, String field) {
        if (!(value instanceof List<?>)) {
            return failure("EXECUTOR_INVALID_PLAN", field + "必须是数组");
        }
        return DbResult.ok(null);
    }

    // 校验CreateTable中的列定义数组
    private DbResult<Void> validateColumnDefinitions(Object value) {
        if (!(value instanceof List<?> list)) {
            return failure("EXECUTOR_INVALID_PLAN", "columns必须是数组");
        }
        Set<String> names = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)
                    || !(map.get("name") instanceof String name)
                    || name.isBlank()
                    || !(map.get("dataType") instanceof String dataType)
                    || !("INT".equalsIgnoreCase(dataType) || "VARCHAR".equalsIgnoreCase(dataType))) {
                return failure("EXECUTOR_INVALID_PLAN", "columns中的列定义无效");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                return failure("EXECUTOR_INVALID_PLAN", "columns中不能包含重复列");
            }
        }
        return DbResult.ok(null);
    }

    // 创建统一格式的计划错误
    private <T> DbResult<T> failure(String code, String message) {
        return DbResult.fail(new DbError(
                null,
                null,
                "EXECUTOR",
                code,
                message,
                null,
                null,
                null
        ));
    }
}
