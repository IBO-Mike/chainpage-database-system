package edu.csu.chainpage.engine.regression;

import edu.csu.chainpage.engine.api.DatabaseApi;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.catalog.StorageCatalogRepository;
import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.executor.core.CreateTableExecutor;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.OptimizedExecutionRequest;
import edu.csu.chainpage.engine.plan.OptimizedPlanExecutor;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.plan.PlanParser;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.CoreEngineFixture;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import edu.csu.chainpage.engine.tx.AuthorizationService;
import edu.csu.chainpage.engine.tx.LockManager;
import edu.csu.chainpage.engine.tx.PlanActionResolver;
import edu.csu.chainpage.engine.tx.TransactionManager;
import edu.csu.chainpage.engine.tx.TransactionalPageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 汇总回归数据库引擎核心、扩展、说明、恢复和事务能力
class DatabaseEngineRegressionTest {

    @Test
    void coreSqlRegression() {
        CoreEngineFixture fixture = new CoreEngineFixture();

        var result = fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(1, "Alice"),
                fixture.insertPlan(2, "Bob"),
                fixture.projectPlan(List.of("*"), fixture.scanPlan()),
                fixture.deletePlan(fixture.binary(
                        "=", fixture.identifier("id"), fixture.literal("INT", 1)
                )),
                fixture.projectPlan(List.of("*"), fixture.scanPlan())
        );

        assertTrue(result.isOk());
        assertEquals(6, result.data().getResults().size());
        assertEquals(List.of(List.of(2, "Bob")), command(result.data(), 5).rows());
    }

    @Test
    void optimizedAndExtendedPlanRegression() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        var extended = fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(1, "Alice"),
                fixture.insertPlan(2, "Bob"),
                updatePlan(),
                project(List.of("*"), sortPlan()),
                project(List.of("name", "row_count"), groupPlan()),
                createScorePlan(),
                insertScorePlan(),
                project(List.of("*"), joinPlan())
        );

        assertTrue(extended.isOk());
        assertEquals(9, extended.data().getResults().size());
        assertEquals(1, command(extended.data(), 3).affectedRows());
        assertEquals(List.of(List.of(2, "Bob"), List.of(1, "changed")),
                command(extended.data(), 4).rows());
        assertEquals(2, command(extended.data(), 5).rows().size());
        assertEquals(List.of(List.of(1, "changed", 1, "A")),
                command(extended.data(), 8).rows());

        var indexUnavailable = fixture.execute(indexPlan());
        assertTrue(indexUnavailable.isOk());
        assertEquals("INDEX_UNAVAILABLE", indexUnavailable.data().getError().getCode());

        PlanDispatcher dispatcher = new PlanDispatcher();
        dispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                return DbResult.ok(ExecutionValue.command(CommandResult.select(
                        List.of("source"),
                        List.of(List.of(((JsonPlanNode) plan).field("table")))
                )));
            }

            @Override
            public boolean supports(String kind) {
                return "SeqScan".equals(kind);
            }
        });
        JsonPlanNode original = scanNode("original", List.of());
        JsonPlanNode optimized = scanNode("optimized", List.of());
        var optimizedResult = new OptimizedPlanExecutor(dispatcher).execute(
                "req-optimized",
                new OptimizedExecutionRequest(original, optimized, List.of("RuleA"))
        );
        assertEquals(List.of(List.of("optimized")),
                optimizedResult.data().result().commandResult().rows());
    }

    @Test
    void explainRegression() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        Map<String, Object> plan = scan("student", studentSchema());
        compiler.enqueueSuccess(new CompileResponse(
                "req-explain",
                List.of(new CompiledStatement(
                        0, List.of("EXPLAIN"), Map.of("kind", "ExplainStatement"),
                        Map.of("valid", true), plan, plan
                ))
        ));
        FakePageStorageClient pages = new FakePageStorageClient();
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())), pages
        );
        lifecycle.startup("startup");
        pages.clearCalls();
        AtomicInteger executions = new AtomicInteger();
        DatabaseApi api = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler,
                (requestId, statementIndex, rawPlan) -> {
                    executions.incrementAndGet();
                    return DbResult.ok(new StatementExecutionResult(statementIndex, "SELECT", rawPlan));
                }
        );

        var result = api.handleExplain("req-explain", "EXPLAIN SELECT * FROM student;");

        assertTrue(result.isOk());
        assertEquals("SeqScan table=student", result.data().tree());
        assertEquals(0, executions.get());
        assertEquals(0, pages.callCount(FakePageStorageClient.WRITE_PAGE));
    }

    @Test
    void restartAndTransactionRegression() {
        FakePageStorageClient persistentPages = new FakePageStorageClient();
        CoreEngineFixture beforeRestart = new CoreEngineFixture(persistentPages);
        beforeRestart.execute(beforeRestart.createTablePlan(), beforeRestart.insertPlan(5, "Eve"));
        beforeRestart.lifecycle.shutdown("shutdown");
        CoreEngineFixture afterRestart = new CoreEngineFixture(persistentPages);
        var selected = afterRestart.execute(afterRestart.projectPlan(
                List.of("*"), afterRestart.scanPlan()
        ));
        assertEquals(List.of(List.of(5, "Eve")), command(selected.data(), 0).rows());

        FakePageStorageClient txPages = new FakePageStorageClient();
        TransactionalPageStorageClient transactionalPages = new TransactionalPageStorageClient(txPages);
        StorageEngine storageEngine = new StorageEngine(transactionalPages);
        SystemCatalogManager catalog = new SystemCatalogManager(
                new StorageCatalogRepository(storageEngine)
        );
        catalog.initialize("tx-init");
        PlanDispatcher dispatcher = new PlanDispatcher();
        dispatcher.register(new CreateTableExecutor(storageEngine, catalog));
        AuthorizationService authorization = new AuthorizationService();
        authorization.grant("alice", "CREATE", "student");
        LockManager locks = new LockManager();
        TransactionManager transactions = new TransactionManager(
                dispatcher, transactionalPages, storageEngine, catalog,
                locks, authorization, new PlanActionResolver()
        );
        long txId = transactions.begin("session").data().txId();
        PlanNode create = new PlanParser().parse(createStudentPlan()).data();

        assertTrue(transactions.execute("tx-create", txId, create, "alice").isOk());
        assertTrue(transactions.rollback("tx-rollback", txId).isOk());
        assertFalse(catalog.containsTable("student"));
        assertTrue(locks.acquireWrite("lock-check", 99, "student").isOk());
        assertFalse(authorization.requireAllowed("bob", "CREATE", "student").isOk());
    }

    // 取得数据库响应中的命令结果
    private CommandResult command(edu.csu.chainpage.engine.api.DatabaseResponse response, int index) {
        StatementExecutionResult statement = (StatementExecutionResult) response.getResults().get(index);
        return (CommandResult) statement.getResult();
    }

    // 构造更新计划
    private Map<String, Object> updatePlan() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("table", "student");
        fields.put("assignments", List.of(Map.of(
                "column", "name",
                "value", literal("VARCHAR", "changed")
        )));
        fields.put("predicate", Map.of(
                "kind", "BinaryExpr", "operator", "=",
                "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                "right", literal("INT", 1)
        ));
        return plan("Update", fields, List.of(), List.of());
    }

    // 构造倒序排序计划
    private Map<String, Object> sortPlan() {
        return plan(
                "Sort",
                Map.of("keys", List.of(Map.of("column", "id", "direction", "DESC"))),
                List.of(scan("student", studentSchema())),
                studentSchema()
        );
    }

    // 构造按姓名分组计数计划
    private Map<String, Object> groupPlan() {
        List<Map<String, String>> schema = List.of(
                Map.of("name", "name", "dataType", "VARCHAR"),
                Map.of("name", "row_count", "dataType", "INT")
        );
        return plan(
                "GroupBy",
                Map.of(
                        "keys", List.of("name"),
                        "aggregates", List.of(Map.of(
                                "function", "COUNT", "column", "*", "alias", "row_count"
                        ))
                ),
                List.of(scan("student", studentSchema())),
                schema
        );
    }

    // 构造第二张成绩表
    private Map<String, Object> createScorePlan() {
        return plan(
                "CreateTable",
                Map.of(
                        "table", "score",
                        "columns", List.of(
                                Map.of("name", "student_id", "dataType", "INT"),
                                Map.of("name", "grade", "dataType", "VARCHAR")
                        )
                ),
                List.of(),
                List.of()
        );
    }

    // 构造事务回归使用的学生表建表计划
    private Map<String, Object> createStudentPlan() {
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

    // 构造成绩表插入计划
    private Map<String, Object> insertScorePlan() {
        return plan(
                "Insert",
                Map.of(
                        "table", "score",
                        "columns", List.of("student_id", "grade"),
                        "values", List.of(literal("INT", 1), literal("VARCHAR", "A"))
                ),
                List.of(),
                List.of()
        );
    }

    // 构造学生表与成绩表连接计划
    private Map<String, Object> joinPlan() {
        List<Map<String, String>> scoreSchema = List.of(
                Map.of("name", "student_id", "dataType", "INT"),
                Map.of("name", "grade", "dataType", "VARCHAR")
        );
        List<Map<String, String>> joinedSchema = new ArrayList<>(studentSchema());
        joinedSchema.addAll(scoreSchema);
        return plan(
                "Join",
                Map.of("leftKey", "id", "rightKey", "student_id"),
                List.of(scan("student", studentSchema()), scan("score", scoreSchema)),
                joinedSchema
        );
    }

    // 构造索引扫描计划
    private Map<String, Object> indexPlan() {
        return plan(
                "IndexScan",
                Map.of(
                        "table", "student",
                        "index", "idx_student_id",
                        "condition", Map.of("kind", "IdentifierExpr", "name", "id")
                ),
                List.of(),
                studentSchema()
        );
    }

    // 在任意行集计划外增加投影节点
    private Map<String, Object> project(List<String> columns, Map<String, Object> child) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> schema = (List<Map<String, String>>) child.get("schema");
        return plan("Project", Map.of("columns", columns), List.of(child), schema);
    }

    // 构造顺序扫描计划
    private Map<String, Object> scan(String table, List<Map<String, String>> schema) {
        return plan("SeqScan", Map.of("table", table), List.of(), schema);
    }

    // 构造JsonPlanNode形式的顺序扫描计划
    private JsonPlanNode scanNode(String table, List<edu.csu.chainpage.engine.contract.ColumnSchema> schema) {
        return new JsonPlanNode("SeqScan", Map.of("table", table), List.of(), schema);
    }

    // 构造学生表输出模式
    private List<Map<String, String>> studentSchema() {
        return List.of(
                Map.of("name", "id", "dataType", "INT"),
                Map.of("name", "name", "dataType", "VARCHAR")
        );
    }

    // 构造字面量表达式
    private Map<String, Object> literal(String type, Object value) {
        return Map.of("kind", "LiteralExpr", "literalType", type, "value", value);
    }

    // 构造带公共字段的完整计划
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
