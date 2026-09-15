package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.catalog.StorageCatalogRepository;
import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.FakePageStorageClient;

import java.util.List;
import java.util.Map;

// 为核心执行器测试创建可重复使用的目录、存储和计划夹具
final class CoreExecutorTestSupport {

    final FakePageStorageClient pageStorageClient = new FakePageStorageClient();
    final StorageEngine storageEngine = new StorageEngine(pageStorageClient);
    final SystemCatalogManager catalogManager = new SystemCatalogManager(
            new StorageCatalogRepository(storageEngine)
    );

    // 初始化系统目录
    void initialize() {
        DbResult<Void> result = catalogManager.initialize("test-init");
        if (!result.isOk()) {
            throw new AssertionError(result.error().getMessage());
        }
    }

    // 创建基础student表并插入两条测试记录
    void createStudentTable() {
        CreateTableExecutor executor = new CreateTableExecutor(storageEngine, catalogManager);
        DbResult<?> created = executor.execute("create-student", createTablePlan());
        if (!created.isOk()) {
            throw new AssertionError(created.error().getMessage());
        }
        InsertExecutor insert = new InsertExecutor(storageEngine, catalogManager);
        if (!insert.execute("insert-1", insertPlan(1, "Alice")).isOk()
                || !insert.execute("insert-2", insertPlan(2, "Bob")).isOk()) {
            throw new AssertionError("failed to insert fixture rows");
        }
    }

    // 构造建表计划
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

    // 构造单行插入计划
    JsonPlanNode insertPlan(int id, String name) {
        return new JsonPlanNode(
                "Insert",
                Map.of(
                        "kind", "Insert",
                        "table", "student",
                        "columns", List.of("id", "name"),
                        "values", List.of(
                                Map.of("kind", "LiteralExpr", "literalType", "INT", "value", id),
                                Map.of("kind", "LiteralExpr", "literalType", "VARCHAR", "value", name)
                        )
                ),
                List.of(),
                List.of()
        );
    }

    // 构造顺序扫描计划
    JsonPlanNode scanPlan() {
        return new JsonPlanNode(
                "SeqScan",
                Map.of("kind", "SeqScan", "table", "student"),
                List.of(),
                List.of(new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"))
        );
    }

    // 构造目录表定义
    TableSchema contractSchema() {
        return new TableSchema(
                "student",
                List.of(new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"))
        );
    }
}
