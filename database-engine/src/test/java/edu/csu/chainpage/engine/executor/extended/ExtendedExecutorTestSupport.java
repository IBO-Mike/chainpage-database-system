package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.catalog.StorageCatalogRepository;
import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.core.CreateTableExecutor;
import edu.csu.chainpage.engine.executor.core.ExecutorSupport;
import edu.csu.chainpage.engine.executor.core.InsertExecutor;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.storage.ColumnSchema;
import edu.csu.chainpage.engine.storage.InternalRow;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.RowId;
import edu.csu.chainpage.engine.storage.RowSet;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.FakePageStorageClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 为阶段8执行器测试提供真实目录、存储和常用计划对象
final class ExtendedExecutorTestSupport {

    final FakePageStorageClient pageStorageClient = new FakePageStorageClient();
    final StorageEngine storageEngine = new StorageEngine(pageStorageClient);
    final SystemCatalogManager catalogManager = new SystemCatalogManager(
            new StorageCatalogRepository(storageEngine)
    );

    // 初始化目录并创建包含三条记录的student表
    void initializeStudentTable() {
        assertSuccess(catalogManager.initialize("extended-init"));
        assertSuccess(new CreateTableExecutor(storageEngine, catalogManager)
                .execute("create-student", createTablePlan()));
        InsertExecutor insert = new InsertExecutor(storageEngine, catalogManager);
        assertSuccess(insert.execute("insert-1", insertPlan(1, "Alice")));
        assertSuccess(insert.execute("insert-2", insertPlan(2, "Bob")));
        assertSuccess(insert.execute("insert-3", insertPlan(3, "Cara")));
    }

    // 返回student表的对外目录结构
    edu.csu.chainpage.engine.contract.TableSchema contractSchema() {
        return new edu.csu.chainpage.engine.contract.TableSchema(
                "student",
                List.of(
                        new edu.csu.chainpage.engine.contract.ColumnSchema("id", "INT"),
                        new edu.csu.chainpage.engine.contract.ColumnSchema("name", "VARCHAR")
                )
        );
    }

    // 扫描并返回student表当前所有记录
    RowSet scanStudentRows() {
        DbResult<RowSet> scanned = storageEngine.scanRows(
                "scan-student",
                ExecutorSupport.toStorageSchema(contractSchema())
        );
        if (!scanned.isOk()) {
            throw new AssertionError(scanned.error().getMessage());
        }
        return scanned.data();
    }

    // 构造CreateTable计划
    JsonPlanNode createTablePlan() {
        return new JsonPlanNode(
                "CreateTable",
                Map.of(
                        "kind", "CreateTable",
                        "table", "student",
                        "columns", List.of(
                                Map.of("name", "id", "dataType", "INT"),
                                Map.of("name", "name", "dataType", "VARCHAR")
                        )
                ),
                List.of(),
                List.of()
        );
    }

    // 构造Insert计划
    JsonPlanNode insertPlan(int id, String name) {
        return new JsonPlanNode(
                "Insert",
                Map.of(
                        "kind", "Insert",
                        "table", "student",
                        "columns", List.of("id", "name"),
                        "values", List.of(literal("INT", id), literal("VARCHAR", name))
                ),
                List.of(),
                List.of()
        );
    }

    // 构造Update计划；predicate为null时更新全部记录
    JsonPlanNode updatePlan(List<Map<String, Object>> assignments, Object predicate) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "Update");
        fields.put("table", "student");
        fields.put("assignments", assignments);
        fields.put("predicate", predicate);
        return new JsonPlanNode("Update", fields, List.of(), List.of());
    }

    // 构造一项更新赋值
    Map<String, Object> assignment(String column, Object value) {
        return Map.of("column", column, "value", value);
    }

    // 构造字面量表达式
    Map<String, Object> literal(String type, Object value) {
        return Map.of("kind", "LiteralExpr", "literalType", type, "value", value);
    }

    // 构造列标识符表达式
    Map<String, Object> identifier(String name) {
        return Map.of("kind", "IdentifierExpr", "name", name);
    }

    // 构造二元表达式
    Map<String, Object> binary(String operator, Object left, Object right) {
        return Map.of(
                "kind", "BinaryExpr",
                "operator", operator,
                "left", left,
                "right", right
        );
    }

    // 使用给定模式和值创建带物理位置的测试行集
    static RowSet rowSet(List<ColumnSchema> schema, List<Map<String, Object>> values) {
        List<InternalRow> rows = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            rows.add(new InternalRow(new RowId(0, index), new Row(values.get(index))));
        }
        return new RowSet(schema, rows);
    }

    // 把夹具准备过程中的失败转换成明确的测试失败
    private void assertSuccess(DbResult<?> result) {
        if (!result.isOk()) {
            throw new AssertionError(result.error().getMessage());
        }
    }
}
