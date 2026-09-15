package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.executor.core.ExecutorSupport;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.storage.InternalRow;
import edu.csu.chainpage.engine.storage.RowId;
import edu.csu.chainpage.engine.storage.RowSet;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.storage.index.IndexLookup;
import edu.csu.chainpage.engine.storage.index.StorageIndexLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// 执行IndexScan计划，通过索引取得RowId后逐条回表读取记录
public final class IndexScanExecutor implements PlanExecutor {

    private final IndexLookup indexLookup; // 索引可用性检查和记录定位入口
    private final StorageEngine storageEngine; // 根据RowId回表读取记录
    private final SystemCatalogManager catalogManager; // 查询目标表的真实结构

    // 创建通过StorageEngine访问索引的扫描执行器
    public IndexScanExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager) {
        this(new StorageIndexLookup(storageEngine), storageEngine, catalogManager);
    }

    // 提供目录在前的依赖顺序
    public IndexScanExecutor(
            SystemCatalogManager catalogManager,
            StorageEngine storageEngine) {
        this(storageEngine, catalogManager);
    }

    // 创建可以替换索引查询实现的扫描执行器，并保持存储依赖在前
    public IndexScanExecutor(
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager,
            IndexLookup indexLookup) {
        this(indexLookup, storageEngine, catalogManager);
    }

    // 创建可以替换索引查询实现的扫描执行器
    public IndexScanExecutor(
            IndexLookup indexLookup,
            StorageEngine storageEngine,
            SystemCatalogManager catalogManager) {
        this.indexLookup = Objects.requireNonNull(indexLookup, "indexLookup cannot be null");
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine cannot be null");
        this.catalogManager = Objects.requireNonNull(catalogManager, "catalogManager cannot be null");
    }

    // 仅接受IndexScan计划节点
    @Override
    public boolean supports(String kind) {
        return "IndexScan".equals(kind);
    }

    // 检查索引、取得匹配RowId并回表生成与SeqScan相同结构的内部行集
    @Override
    public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
        DbResult<Void> validPlan = validateIndexPlan(plan);
        if (!validPlan.isOk()) {
            return ExecutorSupport.failureFrom(validPlan.error(), requestId);
        }
        JsonPlanNode jsonPlan = (JsonPlanNode) plan;
        String tableName = ((String) jsonPlan.field("table")).toLowerCase(java.util.Locale.ROOT);
        String indexName = ((String) jsonPlan.field("index")).toLowerCase(java.util.Locale.ROOT);
        Object condition = jsonPlan.field("condition");

        DbResult<Optional<TableSchema>> table = catalogManager.getTable(requestId, tableName);
        if (!table.isOk()) {
            return DbResult.fail(table.error());
        }
        if (table.data().isEmpty()) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_TABLE_NOT_FOUND",
                    "目录中不存在表：" + tableName
            );
        }
        if (!schemaMatches(plan.schema(), table.data().get().getColumns())) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_INDEX_SCHEMA_MISMATCH",
                    "IndexScan输出模式与目录中的表结构不一致"
            );
        }

        DbResult<Void> available = indexLookup.ensureAvailable(requestId, tableName, indexName);
        if (available == null) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_INVALID_INDEX_RESPONSE",
                    "索引查询器未返回可用性结果"
            );
        }
        if (!available.isOk()) {
            return ExecutorSupport.failureFrom(available.error(), requestId);
        }
        DbResult<List<RowId>> rowIds = indexLookup.find(
                requestId,
                tableName,
                indexName,
                condition
        );
        if (rowIds == null) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_INVALID_INDEX_RESPONSE",
                    "索引查询器未返回记录位置结果"
            );
        }
        if (!rowIds.isOk()) {
            return ExecutorSupport.failureFrom(rowIds.error(), requestId);
        }
        DbResult<RowSet> rows = loadRows(
                requestId,
                ExecutorSupport.toStorageSchema(table.data().get()),
                rowIds.data()
        );
        if (!rows.isOk()) {
            return DbResult.fail(rows.error());
        }
        return DbResult.ok(ExecutionValue.rows(rows.data()));
    }

    // 将索引返回的RowId逐条转换成仍然有效的内部记录
    public DbResult<RowSet> loadRows(
            String requestId,
            edu.csu.chainpage.engine.storage.TableSchema schema,
            List<RowId> rowIds) {
        if (schema == null) {
            return ExecutorSupport.failure(requestId, "EXECUTOR_INVALID_PLAN", "回表读取的表结构不能为null");
        }
        if (rowIds == null) {
            return ExecutorSupport.failure(
                    requestId,
                    "EXECUTOR_INVALID_INDEX_RESPONSE",
                    "索引查询器返回的记录位置列表不能为null"
            );
        }

        List<InternalRow> rows = new ArrayList<>();
        for (RowId rowId : rowIds) {
            if (rowId == null) {
                return ExecutorSupport.failure(
                        requestId,
                        "EXECUTOR_INVALID_INDEX_ROW_ID",
                        "索引查询器返回了无效RowId"
                );
            }
            DbResult<InternalRow> row = storageEngine.readRow(requestId, schema, rowId);
            if (!row.isOk()) {
                return DbResult.fail(row.error());
            }
            if (row.data() == null) {
                return ExecutorSupport.failure(
                        requestId,
                        "EXECUTOR_INVALID_STORAGE_RESPONSE",
                        "存储引擎未返回索引命中的记录"
                );
            }
            rows.add(row.data());
        }
        return DbResult.ok(new RowSet(schema.getColumns(), rows));
    }

    // 检查IndexScan的表、索引、条件、子计划数量和输出模式字段
    public DbResult<Void> validateIndexPlan(PlanNode plan) {
        if (plan == null || !supports(plan.kind())) {
            return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "IndexScan执行器收到错误节点");
        }
        DbResult<JsonPlanNode> jsonPlan = ExecutorSupport.asJsonPlan(null, plan);
        if (!jsonPlan.isOk()) {
            return DbResult.fail(jsonPlan.error());
        }
        if (plan.children() == null || !plan.children().isEmpty()) {
            return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "IndexScan节点不能包含子计划");
        }
        if (plan.schema() == null || plan.schema().isEmpty()
                || plan.schema().stream().anyMatch(Objects::isNull)) {
            return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "IndexScan节点必须包含有效输出模式");
        }
        DbResult<String> table = ExecutorSupport.requiredString(null, jsonPlan.data(), "table");
        if (!table.isOk()) {
            return DbResult.fail(table.error());
        }
        DbResult<String> index = ExecutorSupport.requiredString(null, jsonPlan.data(), "index");
        if (!index.isOk()) {
            return DbResult.fail(index.error());
        }
        Object condition = jsonPlan.data().field("condition");
        if (!(condition instanceof Map<?, ?> expression)
                || !(expression.get("kind") instanceof String kind)
                || kind.isBlank()) {
            return ExecutorSupport.failure(null, "EXECUTOR_INVALID_PLAN", "IndexScan节点必须包含有效condition表达式");
        }
        return DbResult.ok(null);
    }

    // 比较计划输出模式与目录表结构的列顺序、名称和类型
    private boolean schemaMatches(List<ColumnSchema> planSchema, List<ColumnSchema> tableSchema) {
        if (planSchema.size() != tableSchema.size()) {
            return false;
        }
        for (int position = 0; position < planSchema.size(); position++) {
            ColumnSchema planned = planSchema.get(position);
            ColumnSchema actual = tableSchema.get(position);
            if (!planned.getName().equalsIgnoreCase(actual.getName())
                    || !planned.getDataType().equalsIgnoreCase(actual.getDataType())) {
                return false;
            }
        }
        return true;
    }
}
