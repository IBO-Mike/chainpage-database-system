package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.catalog.StorageCatalogRepository;
import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.executor.core.CreateTableExecutor;
import edu.csu.chainpage.engine.executor.core.DeleteExecutor;
import edu.csu.chainpage.engine.executor.core.FilterExecutor;
import edu.csu.chainpage.engine.executor.core.InsertExecutor;
import edu.csu.chainpage.engine.executor.core.ProjectExecutor;
import edu.csu.chainpage.engine.executor.core.SeqScanExecutor;
import edu.csu.chainpage.engine.executor.extended.UpdateExecutor;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.plan.PlanParser;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.FakePageStorageClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 为事务测试连接目录、存储、执行器、权限和锁服务
final class TransactionTestFixture {

    final FakePageStorageClient pages = new FakePageStorageClient();
    final TransactionalPageStorageClient transactionalPages = new TransactionalPageStorageClient(pages);
    final StorageEngine storageEngine = new StorageEngine(transactionalPages);
    final SystemCatalogManager catalogManager = new SystemCatalogManager(
            new StorageCatalogRepository(storageEngine)
    );
    final PlanDispatcher dispatcher = new PlanDispatcher();
    final LockManager lockManager = new LockManager();
    final AuthorizationService authorization = new AuthorizationService();
    final TransactionManager transactionManager;

    // 初始化持久化目录并注册事务测试所需执行器
    TransactionTestFixture() {
        if (!catalogManager.initialize("fixture-init").isOk()) {
            throw new AssertionError("事务测试目录初始化失败");
        }
        dispatcher.register(new CreateTableExecutor(storageEngine, catalogManager));
        dispatcher.register(new InsertExecutor(storageEngine, catalogManager));
        dispatcher.register(new SeqScanExecutor(storageEngine, catalogManager));
        dispatcher.register(new FilterExecutor(dispatcher));
        dispatcher.register(new ProjectExecutor(dispatcher));
        dispatcher.register(new DeleteExecutor(storageEngine, catalogManager));
        dispatcher.register(new UpdateExecutor(storageEngine, catalogManager));
        transactionManager = new TransactionManager(
                dispatcher,
                transactionalPages,
                storageEngine,
                catalogManager,
                lockManager,
                authorization,
                new PlanActionResolver()
        );
    }

    // 解析一份测试计划
    PlanNode parse(Map<String, Object> rawPlan) {
        var parsed = new PlanParser().parse(rawPlan);
        if (!parsed.isOk()) {
            throw new AssertionError(parsed.error().getMessage());
        }
        return parsed.data();
    }

    // 构造学生表建表计划
    Map<String, Object> createPlan() {
        return plan(
                "CreateTable",
                Map.of(
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

    // 构造学生表插入计划
    Map<String, Object> insertPlan(int id, String name) {
        return plan(
                "Insert",
                Map.of(
                        "table", "student",
                        "columns", List.of("id", "name"),
                        "values", List.of(literal("INT", id), literal("VARCHAR", name))
                ),
                List.of(),
                List.of()
        );
    }

    // 构造可直接产生查询命令结果的投影扫描计划
    Map<String, Object> selectPlan() {
        Map<String, Object> scan = plan(
                "SeqScan",
                Map.of("table", "student"),
                List.of(),
                schema()
        );
        return plan(
                "Project",
                Map.of("columns", List.of("*")),
                List.of(scan),
                schema()
        );
    }

    // 构造字面量表达式
    private Map<String, Object> literal(String type, Object value) {
        return Map.of("kind", "LiteralExpr", "literalType", type, "value", value);
    }

    // 构造学生表输出模式
    private List<Map<String, String>> schema() {
        return List.of(
                Map.of("name", "id", "dataType", "INT"),
                Map.of("name", "name", "dataType", "VARCHAR")
        );
    }

    // 构造符合规约的完整计划节点
    private Map<String, Object> plan(
            String kind,
            Map<String, Object> fields,
            List<?> children,
            List<?> schema) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", kind);
        plan.putAll(fields);
        plan.put("children", children);
        plan.put("schema", schema);
        return plan;
    }
}
