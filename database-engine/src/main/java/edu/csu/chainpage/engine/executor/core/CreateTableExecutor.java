package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.StorageEngine;

import java.util.Objects;

// 执行CreateTable计划，按存储先行、目录后登记的顺序建立新表
public final class CreateTableExecutor implements PlanExecutor {

    private final StorageEngine storageEngine; // 表数据存储引擎
    private final SystemCatalogManager catalogManager; // 系统目录管理器

    // 创建建表执行器
    public CreateTableExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager cannot be null");
    }

    // 提供目录在前的依赖顺序，便于与调用方的目录管理习惯保持一致
    public CreateTableExecutor(
            SystemCatalogManager catalogManager,
            StorageEngine storageEngine) {
        this(storageEngine, catalogManager);
    }

    // 仅接受CreateTable计划节点
    @Override
    public boolean supports(String kind) {
        return "CreateTable".equals(kind);
    }

    // 创建物理表存储并登记目录，目录失败时执行补偿删除
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "CreateTable执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(requestId, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        DbResult<TableSchema> schemaResult = readSchema(jsonPlan.data());
        if (!schemaResult.isOk()) {
            return ExecutorSupport.failureFrom(schemaResult.error(), requestId);
        }
        TableSchema schema = schemaResult.data();

        DbResult<edu.csu.chainpage.engine.contract.TablePages> created =
                storageEngine.createTableStorage(
                        requestId,
                        ExecutorSupport.toStorageSchema(schema)
                );
        if (!created.isOk()) {
            return DbResult.fail(created.error());
        }
        if (created.data() == null) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_INVALID_STORAGE_RESPONSE",
                    "存储引擎未返回新建表页映射"
            );
        }

        DbResult<TableSchema> registered = catalogManager.createTable(requestId, schema);
        if (!registered.isOk()) {
            // 目录登记失败时清理本次刚刚创建的物理映射。
            compensateStorage(requestId, schema.getName());
            return DbResult.fail(registered.error());
        }
        return DbResult.ok(ExecutionValue.command(CommandResult.create()));
    }

    // 从CreateTable计划读取并校验表结构
    public DbResult<TableSchema> readSchema(PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "readSchema只接受CreateTable节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(null, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        return ExecutorSupport.tableSchemaFromPlan(null, jsonPlan.data());
    }

    // 目录登记失败时删除刚创建的表页映射
    public DbResult<Void> compensateStorage(String requestId, String table) {
        if (table == null || table.isBlank()) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "补偿删除需要有效表名");
        }
        return storageEngine.dropTableStorage(requestId, table);
    }
}
