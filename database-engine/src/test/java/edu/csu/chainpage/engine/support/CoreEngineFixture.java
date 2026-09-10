package edu.csu.chainpage.engine.support;

import edu.csu.chainpage.engine.api.DatabaseApi;
import edu.csu.chainpage.engine.api.DatabaseResponse;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.catalog.StorageCatalogRepository;
import edu.csu.chainpage.engine.catalog.SystemCatalogManager;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.core.CreateTableExecutor;
import edu.csu.chainpage.engine.executor.core.DeleteExecutor;
import edu.csu.chainpage.engine.executor.core.FilterExecutor;
import edu.csu.chainpage.engine.executor.core.InsertExecutor;
import edu.csu.chainpage.engine.executor.core.ProjectExecutor;
import edu.csu.chainpage.engine.executor.core.SeqScanExecutor;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.plan.PlanParser;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// 使用公开契约把模拟编译器、数据库入口、执行器和模拟页存储连接起来
public final class CoreEngineFixture {

    public final FakePageStorageClient pageStorageClient = new FakePageStorageClient();
    public final FakeSqlCompilerClient compilerClient = new FakeSqlCompilerClient();
    public final StorageEngine storageEngine = new StorageEngine(pageStorageClient);
    public final SystemCatalogManager catalogManager = new SystemCatalogManager(
            new StorageCatalogRepository(storageEngine)
    );
    public final PlanDispatcher dispatcher = new PlanDispatcher();
    public final DatabaseLifecycle lifecycle;
    public final DatabaseApi databaseApi;

    private final AtomicInteger requestSequence = new AtomicInteger(1);

    // 创建并启动一套只依赖公开接口的核心数据库夹具
    public CoreEngineFixture() {
        registerExecutors();
        lifecycle = new DatabaseLifecycle(
                requestId -> {
                    DbResult<Void> initialized = catalogManager.initialize(requestId);
                    return initialized.isOk()
                            ? DbResult.ok(catalogManager.snapshot())
                            : DbResult.fail(initialized.error());
                },
                pageStorageClient
        );
        databaseApi = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(catalogManager.snapshot()),
                compilerClient,
                this::executePlan
        );
        DbResult<?> started = lifecycle.startup("fixture-startup");
        if (!started.isOk()) {
            throw new AssertionError(started.error().getMessage());
        }
    }

    // 注册阶段5的六个核心执行器
    private void registerExecutors() {
        dispatcher.register(new CreateTableExecutor(storageEngine, catalogManager));
        dispatcher.register(new InsertExecutor(storageEngine, catalogManager));
        dispatcher.register(new SeqScanExecutor(storageEngine, catalogManager));
        dispatcher.register(new FilterExecutor(dispatcher));
        dispatcher.register(new ProjectExecutor(dispatcher));
        dispatcher.register(new DeleteExecutor(storageEngine, catalogManager));
    }

    // 把编译器计划解析并交给计划分派器执行
    private DbResult<StatementExecutionResult> executePlan(
            String requestId,
            int statementIndex,
            Object rawPlan) {
        DbResult<PlanNode> parsed = new PlanParser().parse(rawPlan);
        if (!parsed.isOk()) {
            return DbResult.fail(withRequest(parsed.error(), requestId));
        }
        DbResult<ExecutionValue> executed = dispatcher.execute(requestId, parsed.data());
        if (!executed.isOk()) {
            return DbResult.fail(executed.error());
        }
        if (executed.data() == null || executed.data().commandResult() == null) {
            return DbResult.fail(new DbError(
                    requestId,
                    statementIndex,
                    "EXECUTOR",
                    "EXECUTOR_INVALID_RESULT",
                    "计划没有返回命令结果",
                    null,
                    null,
                    null
            ));
        }
        CommandResult command = executed.data().commandResult();
        return DbResult.ok(new StatementExecutionResult(statementIndex, command.kind(), command));
    }

    // 让计划解析错误保留当前请求编号
    private DbError withRequest(DbError error, String requestId) {
        return new DbError(
                requestId,
                error.getStatementIndex(),
                error.getStage(),
                error.getCode(),
                error.getMessage(),
                error.getLine(),
                error.getColumn(),
                error.getPageId()
        );
    }

    // 预置一次编译成功响应，按传入顺序生成语句编号
    public void enqueuePlans(Object... plans) {
        List<CompiledStatement> statements = new ArrayList<>();
        for (int index = 0; index < plans.length; index++) {
            statements.add(new CompiledStatement(
                    index,
                    List.of(Map.of("kind", "Token")),
                    Map.of("kind", "Statement"),
                    Map.of("kind", "Semantic"),
                    plans[index],
                    null
            ));
        }
        compilerClient.enqueueSuccess(new CompileResponse(
                "fixture-compile",
                statements
        ));
    }

    // 向入口提交一组由模拟编译器预置的计划
    public DbResult<DatabaseResponse> execute(Object... plans) {
        return execute("req-" + requestSequence.getAndIncrement(), "fixture SQL", plans);
    }

    // 使用指定请求编号和SQL文本提交一组计划
    public DbResult<DatabaseResponse> execute(
            String requestId,
            String sql,
            Object... plans) {
        enqueuePlans(plans);
        return databaseApi.handle(
                requestId,
                new edu.csu.chainpage.engine.api.DatabaseRequest(sql, "execute")
        );
    }

    // 构造建表计划
    public Map<String, Object> createTablePlan() {
        return createTablePlan("student");
    }

    // 构造指定表名的建表计划
    public Map<String, Object> createTablePlan(String table) {
        return plan(
                "CreateTable",
                Map.of(
                        "table", table,
                        "columns", List.of(
                                Map.of("name", "id", "dataType", "INT"),
                                Map.of("name", "name", "dataType", "VARCHAR")
                        )
                ),
                List.of(),
                List.of()
        );
    }

    // 构造单条插入计划
    public Map<String, Object> insertPlan(int id, String name) {
        return plan(
                "Insert",
                Map.of(
                        "table", "student",
                        "columns", List.of("id", "name"),
                        "values", List.of(
                                literal("INT", id),
                                literal("VARCHAR", name)
                        )
                ),
                List.of(),
                List.of()
        );
    }

    // 构造顺序扫描计划
    public Map<String, Object> scanPlan() {
        return plan(
                "SeqScan",
                Map.of("table", "student"),
                List.of(),
                List.of(
                        Map.of("name", "id", "dataType", "INT"),
                        Map.of("name", "name", "dataType", "VARCHAR")
                )
        );
    }

    // 构造指定谓词的过滤计划
    public Map<String, Object> filterPlan(Object predicate) {
        return plan(
                "Filter",
                Map.of("predicate", predicate),
                List.of(scanPlan()),
                List.of(
                        Map.of("name", "id", "dataType", "INT"),
                        Map.of("name", "name", "dataType", "VARCHAR")
                )
        );
    }

    // 构造指定列的投影计划
    public Map<String, Object> projectPlan(List<String> columns, Object child) {
        return plan(
                "Project",
                Map.of("columns", columns),
                List.of(child),
                List.of(
                        Map.of("name", "id", "dataType", "INT"),
                        Map.of("name", "name", "dataType", "VARCHAR")
                )
        );
    }

    // 构造可选谓词的删除计划；null表示删除全部记录
    public Map<String, Object> deletePlan(Object predicate) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("table", "student");
        fields.put("predicate", predicate);
        return plan("Delete", fields, List.of(), List.of());
    }

    // 构造INT字面量或VARCHAR字面量
    public Map<String, Object> literal(String type, Object value) {
        return Map.of(
                "kind", "LiteralExpr",
                "literalType", type,
                "value", value
        );
    }

    // 构造列标识符表达式
    public Map<String, Object> identifier(String name) {
        return Map.of("kind", "IdentifierExpr", "name", name);
    }

    // 构造二元表达式
    public Map<String, Object> binary(String operator, Object left, Object right) {
        return Map.of(
                "kind", "BinaryExpr",
                "operator", operator,
                "left", left,
                "right", right
        );
    }

    // 构造带kind、children和schema的计划对象
    private Map<String, Object> plan(
            String kind,
            Map<String, Object> fields,
            List<?> children,
            List<Map<String, String>> schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.putAll(fields);
        result.put("children", children);
        result.put("schema", schema);
        return result;
    }
}
