package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.OptimizedExecutionRequest;
import edu.csu.chainpage.engine.plan.OptimizedPlanExecutor;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证DatabaseApi能够调用优化执行器并在未就绪时拒绝请求
class OptimizedExecutionApiTest {

    @Test
    void executesOptimizedPlanThroughDatabaseApi() {
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
        DatabaseLifecycle lifecycle = readyLifecycle();
        DatabaseApi api = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                new FakeSqlCompilerClient(),
                (requestId, statementIndex, plan) -> DbResult.ok(
                        new StatementExecutionResult(statementIndex, "SELECT", plan)
                ),
                new OptimizedPlanExecutor(dispatcher)
        );
        JsonPlanNode original = scanPlan("original");
        JsonPlanNode optimized = scanPlan("optimized");

        var result = api.executeOptimized(
                "req-api-optimized",
                new OptimizedExecutionRequest(original, optimized, List.of("RuleA"))
        );

        assertTrue(result.isOk());
        assertEquals(optimized, result.data().plan());
        assertEquals(List.of("RuleA"), result.data().appliedRules());
        assertEquals(List.of(List.of("optimized")), result.data().result().commandResult().rows());
    }

    @Test
    void rejectsOptimizedExecutionWhenDatabaseIsNotReadyOrExecutorMissing() {
        FakePageStorageClient storage = new FakePageStorageClient();
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                storage
        );
        DatabaseApi notReady = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                new FakeSqlCompilerClient(),
                (requestId, statementIndex, plan) -> DbResult.ok(
                        new StatementExecutionResult(statementIndex, "SELECT", plan)
                )
        );
        var request = new OptimizedExecutionRequest(scanPlan("original"), scanPlan("optimized"), List.of());

        var notReadyResult = notReady.executeOptimized("req-not-ready", request);
        assertFalse(notReadyResult.isOk());
        assertEquals("DATABASE_NOT_READY", notReadyResult.error().getCode());

        lifecycle.startup("startup");
        var unavailable = notReady.executeOptimized("req-unavailable", request);
        assertFalse(unavailable.isOk());
        assertEquals("EXECUTOR_OPTIMIZED_EXECUTOR_UNAVAILABLE", unavailable.error().getCode());
    }

    // 创建已经启动的生命周期管理器
    private DatabaseLifecycle readyLifecycle() {
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                new FakePageStorageClient()
        );
        lifecycle.startup("startup");
        return lifecycle;
    }

    // 构造最小顺序扫描计划
    private JsonPlanNode scanPlan(String table) {
        return new JsonPlanNode(
                "SeqScan",
                Map.of("kind", "SeqScan", "table", table),
                List.of(),
                List.of()
        );
    }
}
