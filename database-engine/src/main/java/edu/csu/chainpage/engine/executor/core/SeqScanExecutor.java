package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.RowSet;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.util.Objects;
import java.util.Optional;

// 执行SeqScan计划，读取目标表中所有尚未删除的记录
public final class SeqScanExecutor implements PlanExecutor {

    private final StorageEngine storageEngine; // 表数据存储引擎
    private final SystemCatalogManager catalogManager; // 系统目录管理器

    // 创建顺序扫描执行器
    public SeqScanExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager cannot be null");
    }

    // 提供目录在前的依赖顺序，便于与调用方的目录管理习惯保持一致
    public SeqScanExecutor(
            SystemCatalogManager catalogManager,
            StorageEngine storageEngine) {
        this(storageEngine, catalogManager);
    }

    // 仅接受SeqScan计划节点
    @Override
    public boolean supports(String kind) {
        return "SeqScan".equals(kind);
    }

    // 从目录取得表结构并扫描全部数据页
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "SeqScan执行器收到错误节点");
        }
        DbResult<TableSchema> schema = resolveTable(requestId, plan);
        if (!schema.isOk()) {
            return DbResult.fail(schema.error());
        }
        DbResult<RowSet> scanned = storageEngine.scanRows(
                requestId,
                ExecutorSupport.toStorageSchema(schema.data())
        );
        if (!scanned.isOk()) {
            return DbResult.fail(scanned.error());
        }
        if (scanned.data() == null) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_INVALID_STORAGE_RESPONSE",
                    "存储引擎未返回行集"
            );
        }
        return DbResult.ok(ExecutionValue.rows(scanned.data()));
    }

    // 从目录查询扫描目标表结构
    public DbResult<TableSchema> resolveTable(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "resolveTable只接受SeqScan节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
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
        return DbResult.ok(found.data().get());
    }
}
