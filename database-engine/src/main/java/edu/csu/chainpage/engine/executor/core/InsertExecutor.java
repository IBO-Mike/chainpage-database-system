package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

// 执行Insert计划，把字面量按目录列顺序组装成一条完整记录
public final class InsertExecutor implements PlanExecutor {

    private final StorageEngine storageEngine; // 表数据存储引擎
    private final SystemCatalogManager catalogManager; // 系统目录管理器

    // 创建插入执行器
    public InsertExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager cannot be null");
    }

    // 提供目录在前的依赖顺序，便于与调用方的目录管理习惯保持一致
    public InsertExecutor(
            SystemCatalogManager catalogManager,
            StorageEngine storageEngine) {
        this(storageEngine, catalogManager);
    }

    // 仅接受Insert计划节点
    @Override
    public boolean supports(String kind) {
        return "Insert".equals(kind);
    }

    // 取得目录表结构、组装记录并调用存储引擎插入
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Insert执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        DbResult<String> tableResult = ExecutorSupport.requiredString(requestId, jsonPlan.data(), "table");
        if (!tableResult.isOk()) {
            return DbResult.fail(tableResult.error());
        }
        DbResult<Optional<TableSchema>> table = catalogManager.getTable(requestId, tableResult.data());
        if (!table.isOk()) {
            return DbResult.fail(table.error());
        }
        if (table.data().isEmpty()) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_TABLE_NOT_FOUND",
                    "目录中不存在表：" + tableResult.data()
            );
        }

        DbResult<Row> row = buildRow(table.data().get(), jsonPlan.data());
        if (!row.isOk()) {
            return ExecutorSupport.failureFrom(row.error(), requestId);
        }
        DbResult<edu.csu.chainpage.engine.storage.RowId> inserted = storageEngine.insertRow(
                requestId,
                table.data().get().getName(),
                row.data()
        );
        if (!inserted.isOk()) {
            return DbResult.fail(inserted.error());
        }
        if (inserted.data() == null) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_INVALID_STORAGE_RESPONSE",
                    "存储引擎未返回新记录位置"
            );
        }
        return DbResult.ok(ExecutionValue.command(CommandResult.insert()));
    }

    // 按目录中的完整列顺序建立一条记录
    public DbResult<Row> buildRow(TableSchema schema, PlanNode plan) {
        if (schema == null || plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "buildRow需要Insert计划和有效表结构");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(null, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        Object rawColumns = jsonPlan.data().field("columns");
        Object rawValues = jsonPlan.data().field("values");
        if (!(rawColumns instanceof List<?> columns)
                || !(rawValues instanceof List<?> values)
                || columns.size() != values.size()) {
            return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "Insert的columns和values数量必须一致");
        }
        Map<String, Object> supplied = new HashMap<>();
        Set<String> suppliedNames = new HashSet<>();
        for (int index = 0; index < columns.size(); index++) {
            Object rawColumn = columns.get(index);
            if (!(rawColumn instanceof String column) || column.isBlank()) {
                return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "Insert列名无效");
            }
            String name = column.toLowerCase(Locale.ROOT);
            if (!suppliedNames.add(name)) {
                return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "Insert列名重复：" + name);
            }
            DbResult<Object> value = literalValue(values.get(index));
            if (!value.isOk()) {
                return DbResult.fail(value.error());
            }
            supplied.put(name, value.data());
        }

        Map<String, Object> ordered = new java.util.LinkedHashMap<>();
        for (ColumnSchema column : schema.getColumns()) {
            String name = column.getName().toLowerCase(Locale.ROOT);
            if (!supplied.containsKey(name)) {
                return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "Insert缺少列：" + name);
            }
            ordered.put(name, supplied.get(name));
        }
        if (supplied.size() != schema.getColumns().size()) {
            for (String suppliedName : supplied.keySet()) {
                boolean known = schema.getColumns().stream()
                        .anyMatch(column -> column.getName().equalsIgnoreCase(suppliedName));
                if (!known) {
                    return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "Insert包含未知列：" + suppliedName);
                }
            }
        }
        return DbResult.ok(new Row(ordered));
    }

    // 读取一个LiteralExpr并转换成存储可识别的Java值
    public DbResult<Object> literalValue(Object expression) {
        if (!(expression instanceof Map<?, ?> map)) {
            return ExecutorSupport.failure(null, "EXECUTOR_LITERAL_ERROR", "Insert的值必须是LiteralExpr对象");
        }
        Object rawKind = map.get("kind");
        Object rawType = map.get("literalType");
        if (!"LiteralExpr".equals(rawKind)
                || !(rawType instanceof String literalType)
                || !map.containsKey("value")) {
            return ExecutorSupport.failure(null, "EXECUTOR_LITERAL_ERROR", "Insert只接受完整LiteralExpr");
        }
        Object value = map.get("value");
        return switch (literalType.toUpperCase(Locale.ROOT)) {
            case "INT" -> {
                Integer integer = toInteger(value);
                yield integer == null
                        ? ExecutorSupport.failure(null, "EXECUTOR_LITERAL_ERROR", "INT字面量不是合法整数")
                        : DbResult.ok(integer);
            }
            case "VARCHAR" -> value instanceof String
                    ? DbResult.ok(value)
                    : ExecutorSupport.failure(null, "EXECUTOR_LITERAL_ERROR", "VARCHAR字面量必须是字符串");
            default -> ExecutorSupport.failure(null, "EXECUTOR_LITERAL_ERROR", "不支持的字面量类型：" + literalType);
        };
    }

    // 将整数型Java对象安全转换成32位整数
    private Integer toInteger(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).intValue();
        }
        if (value instanceof Long longValue) {
            return longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE
                    ? longValue.intValue()
                    : null;
        }
        if (value instanceof BigInteger bigInteger) {
            try {
                return bigInteger.intValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        if (value instanceof BigDecimal bigDecimal) {
            try {
                return bigDecimal.intValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        return null;
    }
}
