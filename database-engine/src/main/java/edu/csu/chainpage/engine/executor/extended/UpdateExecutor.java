package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.executor.core.ExecutorSupport;
import edu.csu.chainpage.engine.executor.expression.ExpressionEvaluator;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.InternalRow;
import edu.csu.chainpage.engine.storage.PageRecordCodec;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.RowId;
import edu.csu.chainpage.engine.storage.RowSet;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

// 执行Update计划，定位目标记录并计算每条记录的新列值
public final class UpdateExecutor implements PlanExecutor {

    private final StorageEngine storageEngine; // 表数据存储引擎
    private final SystemCatalogManager catalogManager; // 系统目录管理器
    private final ExpressionEvaluator evaluator; // 谓词和赋值表达式求值器

    // 使用默认表达式求值器创建更新执行器
    public UpdateExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager) {
        this(storageEngine, catalogManager, new ExpressionEvaluator());
    }

    // 提供目录在前的依赖顺序
    public UpdateExecutor(
            SystemCatalogManager catalogManager,
            StorageEngine storageEngine) {
        this(storageEngine, catalogManager, new ExpressionEvaluator());
    }

    // 创建可替换表达式求值器的更新执行器
    public UpdateExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager,
            ExpressionEvaluator evaluator) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator cannot be null");
    }

    // 提供目录在前且可替换表达式求值器的依赖顺序
    public UpdateExecutor(
            SystemCatalogManager catalogManager,
            StorageEngine storageEngine,
            ExpressionEvaluator evaluator) {
        this(storageEngine, catalogManager, evaluator);
    }

    // 仅接受Update计划节点
    @Override
    public boolean supports(String kind) {
        return "Update".equals(kind);
    }

    // 查找目标记录、计算全部赋值并调用存储引擎更新
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Update执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        if (!jsonPlan.data().fields().containsKey("predicate")) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Update节点必须包含predicate字段");
        }
        DbResult<String> tableName = ExecutorSupport.requiredString(requestId, jsonPlan.data(), "table");
        if (!tableName.isOk()) {
            return DbResult.fail(tableName.error());
        }
        DbResult<Optional<TableSchema>> table = catalogManager.getTable(requestId, tableName.data());
        if (!table.isOk()) {
            return DbResult.fail(table.error());
        }
        if (table.data().isEmpty()) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_TABLE_NOT_FOUND",
                    "目录中不存在表：" + tableName.data()
            );
        }
        DbResult<Void> assignmentColumns = validateAssignmentColumns(
                requestId,
                jsonPlan.data(),
                table.data().get()
        );
        if (!assignmentColumns.isOk()) {
            return DbResult.fail(assignmentColumns.error());
        }

        Object predicate = jsonPlan.data().field("predicate");
        DbResult<List<RowId>> targets = findTargetRows(requestId, table.data().get(), predicate);
        if (!targets.isOk()) {
            return DbResult.fail(targets.error());
        }
        if (targets.data().isEmpty()) {
            return DbResult.ok(ExecutionValue.command(CommandResult.update(0)));
        }

        edu.csu.chainpage.engine.storage.TableSchema storageSchema =
                ExecutorSupport.toStorageSchema(table.data().get());
        Map<RowId, Map<String, Object>> evaluatedAssignments = new LinkedHashMap<>();
        PageRecordCodec validator = new PageRecordCodec();
        for (RowId rowId : targets.data()) {
            DbResult<InternalRow> current = storageEngine.readRow(requestId, storageSchema, rowId);
            if (!current.isOk()) {
                return DbResult.fail(current.error());
            }
            DbResult<Map<String, Object>> assignments = evaluateAssignments(plan, current.data().values());
            if (!assignments.isOk()) {
                return ExecutorSupport.failureFrom(assignments.error(), requestId);
            }

            Map<String, Object> updatedValues = new LinkedHashMap<>(current.data().values().values());
            updatedValues.putAll(assignments.data());
            DbResult<Void> validRow = validator.validateRow(storageSchema, new Row(updatedValues));
            if (!validRow.isOk()) {
                return ExecutorSupport.failureFrom(validRow.error(), requestId);
            }
            evaluatedAssignments.put(rowId, assignments.data());
        }

        int updatedCount = 0;
        for (Map.Entry<RowId, Map<String, Object>> entry : evaluatedAssignments.entrySet()) {
            DbResult<Integer> updated = storageEngine.updateRows(
                    requestId,
                    storageSchema,
                    List.of(entry.getKey()),
                    entry.getValue()
            );
            if (!updated.isOk()) {
                return DbResult.fail(updated.error());
            }
            updatedCount += updated.data();
        }
        return DbResult.ok(ExecutionValue.command(CommandResult.update(updatedCount)));
    }

    // 为一条原记录计算计划中所有赋值表达式
    public DbResult<Map<String, Object>> evaluateAssignments(
            PlanNode plan,
            Row currentRow) {
        if (plan == null || !supports(plan.kind()) || currentRow == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_UPDATE_ERROR", "更新计划和原记录不能无效");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(null, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        Object rawAssignments = jsonPlan.data().field("assignments");
        if (!(rawAssignments instanceof List<?> list) || list.isEmpty()) {
            return ExecutorSupport.failure(null, "EXECUTOR_UPDATE_ERROR", "Update的assignments必须是非空数组");
        }

        Map<String, Object> values = new LinkedHashMap<>();
        for (Object rawAssignment : list) {
            if (!(rawAssignment instanceof Map<?, ?> assignment)
                    || !(assignment.get("column") instanceof String column)
                    || column.isBlank()
                    || !assignment.containsKey("value")
                    || assignment.get("value") == null) {
                return ExecutorSupport.failure(null, "EXECUTOR_UPDATE_ERROR", "Update赋值字段不完整");
            }
            String name = column.toLowerCase(Locale.ROOT);
            if (!currentRow.contains(name)) {
                return ExecutorSupport.failure(null, "EXECUTOR_UPDATE_ERROR", "Update引用了不存在的列：" + name);
            }
            if (values.containsKey(name)) {
                return ExecutorSupport.failure(null, "EXECUTOR_UPDATE_ERROR", "Update列重复赋值：" + name);
            }
            DbResult<Object> value = evaluator.evaluate(assignment.get("value"), currentRow);
            if (!value.isOk()) {
                return DbResult.fail(value.error());
            }
            if (value.data() == null) {
                return ExecutorSupport.failure(null, "EXECUTOR_UPDATE_ERROR", "Update暂不支持把列更新为null");
            }
            values.put(name, value.data());
        }
        return DbResult.ok(Map.copyOf(values));
    }

    // 扫描目标表，无谓词时选择全部记录，有谓词时只选择匹配记录
    public DbResult<List<RowId>> findTargetRows(
            String requestId,
            TableSchema schema,
            Object predicate) {
        if (schema == null) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "更新目标表结构不能为null");
        }
        DbResult<RowSet> scanned = storageEngine.scanRows(
                requestId,
                ExecutorSupport.toStorageSchema(schema)
        );
        if (!scanned.isOk()) {
            return DbResult.fail(scanned.error());
        }
        if (scanned.data() == null) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_STORAGE_RESPONSE", "存储引擎未返回行集");
        }

        List<RowId> targets = new ArrayList<>();
        for (InternalRow row : scanned.data().rows()) {
            if (row == null || row.rowId() == null || row.values() == null) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_UPDATE_ERROR", "扫描结果包含无效记录");
            }
            if (predicate == null) {
                targets.add(row.rowId());
                continue;
            }
            DbResult<Boolean> matches = evaluator.evaluatePredicate(predicate, row.values());
            if (!matches.isOk()) {
                return ExecutorSupport.failureFrom(matches.error(), requestId);
            }
            if (Boolean.TRUE.equals(matches.data())) {
                targets.add(row.rowId());
            }
        }
        return DbResult.ok(List.copyOf(targets));
    }

    // 在读取数据前校验赋值列存在且没有重复
    private DbResult<Void> validateAssignmentColumns(
            String requestId,
            JsonPlanNode plan,
            TableSchema schema) {
        Object rawAssignments = plan.field("assignments");
        if (!(rawAssignments instanceof List<?> list) || list.isEmpty()) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Update的assignments必须是非空数组");
        }
        Set<String> names = new HashSet<>();
        for (Object rawAssignment : list) {
            if (!(rawAssignment instanceof Map<?, ?> assignment)
                    || !(assignment.get("column") instanceof String column)
                    || column.isBlank()
                    || !assignment.containsKey("value")
                    || assignment.get("value") == null) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Update赋值字段不完整");
            }
            String name = column.toLowerCase(Locale.ROOT);
            if (!names.add(name)) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Update列重复赋值：" + name);
            }
            boolean known = false;
            for (ColumnSchema candidate : schema.getColumns()) {
                if (candidate.getName().equalsIgnoreCase(name)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                return ExecutorSupport.failure(requestId, "EXECUTOR_UPDATE_ERROR", "Update引用了不存在的列：" + name);
            }
        }
        return DbResult.ok(null);
    }
}
