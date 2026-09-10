package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.executor.expression.ExpressionEvaluator;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.InternalRow;
import edu.csu.chainpage.engine.storage.RowId;
import edu.csu.chainpage.engine.storage.RowSet;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// 执行Delete计划，先扫描出目标RowId，再交给存储引擎标记删除
public final class DeleteExecutor implements PlanExecutor {

    private final StorageEngine storageEngine; // 表数据存储引擎
    private final SystemCatalogManager catalogManager; // 系统目录管理器
    private final ExpressionEvaluator evaluator; // WHERE表达式求值器

    // 使用默认表达式求值器创建删除执行器
    public DeleteExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager) {
        this(storageEngine, catalogManager, new ExpressionEvaluator());
    }

    // 提供目录在前的依赖顺序，便于与调用方的目录管理习惯保持一致
    public DeleteExecutor(
            SystemCatalogManager catalogManager,
            StorageEngine storageEngine) {
        this(storageEngine, catalogManager, new ExpressionEvaluator());
    }

    // 创建可替换表达式求值器的删除执行器
    public DeleteExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager,
            ExpressionEvaluator evaluator) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager cannot be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator cannot be null");
    }

    // 提供目录在前且可替换表达式求值器的依赖顺序
    public DeleteExecutor(
            SystemCatalogManager catalogManager,
            StorageEngine storageEngine,
            ExpressionEvaluator evaluator) {
        this(storageEngine, catalogManager, evaluator);
    }

    // 仅接受Delete计划节点
    @Override
    public boolean supports(String kind) {
        return "Delete".equals(kind);
    }

    // 扫描目标表、收集RowId并调用存储引擎删除
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Delete执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        if (!jsonPlan.data().fields().containsKey("predicate")) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "Delete节点必须包含predicate字段");
        }
        DbResult<String> table = ExecutorSupport.requiredString(requestId, jsonPlan.data(), "table");
        if (!table.isOk()) {
            return DbResult.fail(table.error());
        }
        DbResult<Optional<TableSchema>> found = catalogManager.getTable(requestId, table.data());
        if (!found.isOk()) {
            return DbResult.fail(found.error());
        }
        if (found.data().isEmpty()) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_TABLE_NOT_FOUND", "目录中不存在表：" + table.data());
        }
        Object predicate = jsonPlan.data().field("predicate");
        DbResult<List<RowId>> targets = findTargetRows(requestId, found.data().get(), predicate);
        if (!targets.isOk()) {
            return DbResult.fail(targets.error());
        }
        DbResult<Integer> deleted = storageEngine.deleteRows(
                requestId,
                found.data().get().getName(),
                targets.data()
        );
        if (!deleted.isOk()) {
            return DbResult.fail(deleted.error());
        }
        int count = deleted.data() == null ? 0 : deleted.data();
        return DbResult.ok(ExecutionValue.command(CommandResult.delete(count)));
    }

    // 无谓词时返回全部记录，有谓词时只返回匹配记录的RowId
    public DbResult<List<RowId>> findTargetRows(
            String requestId,
            TableSchema schema,
            Object predicate) {
        if (schema == null) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "删除目标表结构不能为null");
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
                return ExecutorSupport.failure(requestId, "EXECUTOR_DELETE_ERROR", "扫描结果包含无效记录");
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
}
