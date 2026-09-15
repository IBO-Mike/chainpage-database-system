package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证优化执行器只运行优化计划并完整返回优化信息
class OptimizedPlanExecutorTest {

    @Test
    void executesOnlyOptimizedPlanAndReturnsRules() {
        PlanDispatcher dispatcher = new PlanDispatcher();
        List<String> executedTables = new ArrayList<>();
        dispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                String table = ((JsonPlanNode) plan).field("table").toString();
                executedTables.add(table);
                return DbResult.ok(ExecutionValue.command(
                        CommandResult.select(List.of("source"), List.of(List.of(table)))
                ));
            }

            @Override
            public boolean supports(String kind) {
                return "SeqScan".equals(kind);
            }
        });
        PlanNode original = scanPlan("original");
        PlanNode optimized = scanPlan("optimized");
        List<String> rules = new ArrayList<>(List.of("PredicatePushdown", "ProjectionPruning"));
        OptimizedExecutionRequest request = new OptimizedExecutionRequest(original, optimized, rules);

        DbResult<OptimizedExecutionResult> result = new OptimizedPlanExecutor(dispatcher)
                .execute("req-optimized", request);

        assertTrue(result.isOk());
        assertEquals(List.of("optimized"), executedTables);
        assertSame(optimized, result.data().plan());
        assertEquals(rules, result.data().appliedRules());
        assertEquals(List.of(List.of("optimized")), result.data().result().commandResult().rows());
        assertEquals("original", ((JsonPlanNode) original).field("table"));
        rules.add("MustNotLeak");
        assertEquals(2, result.data().appliedRules().size());
    }

    @Test
    void rejectsMissingOptimizedPlanWithoutRunningOriginal() {
        PlanDispatcher dispatcher = new PlanDispatcher();
        int[] calls = {0};
        dispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                calls[0]++;
                return DbResult.ok(ExecutionValue.command(CommandResult.create()));
            }

            @Override
            public boolean supports(String kind) {
                return "SeqScan".equals(kind);
            }
        });
        OptimizedExecutionRequest request = new OptimizedExecutionRequest(
                scanPlan("original"),
                null,
                List.of()
        );

        DbResult<OptimizedExecutionResult> result = new OptimizedPlanExecutor(dispatcher)
                .execute("req-missing", request);

        assertFalse(result.isOk());
        assertEquals("EXECUTOR_OPTIMIZED_PLAN_MISSING", result.error().getCode());
        assertEquals(0, calls[0]);
    }

    @Test
    void returnsOptimizedPlanValidationAndExecutionErrors() {
        JsonPlanNode invalidPlan = new JsonPlanNode(
                "FutureNode",
                Map.of("kind", "FutureNode"),
                List.of(),
                List.of()
        );
        DbResult<OptimizedExecutionResult> invalid = new OptimizedPlanExecutor()
                .execute("req-invalid", new OptimizedExecutionRequest(
                        scanPlan("original"), invalidPlan, List.of()
                ));
        assertFalse(invalid.isOk());
        assertEquals("EXECUTOR_UNSUPPORTED_PLAN", invalid.error().getCode());

        PlanDispatcher failingDispatcher = new PlanDispatcher();
        failingDispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                return DbResult.fail(new DbError(
                        requestId, null, "EXECUTOR", "OPTIMIZED_FAILED",
                        "优化计划执行失败", null, null, null
                ));
            }

            @Override
            public boolean supports(String kind) {
                return "SeqScan".equals(kind);
            }
        });
        DbResult<OptimizedExecutionResult> failed = new OptimizedPlanExecutor(failingDispatcher)
                .execute("req-failed", new OptimizedExecutionRequest(
                        scanPlan("original"), scanPlan("optimized"), List.of("Rule")
                ));

        assertFalse(failed.isOk());
        assertEquals("OPTIMIZED_FAILED", failed.error().getCode());
        assertEquals("req-failed", failed.error().getRequestId());
    }

    // 构造最小可验证的顺序扫描计划
    private JsonPlanNode scanPlan(String table) {
        return new JsonPlanNode(
                "SeqScan",
                Map.of("kind", "SeqScan", "table", table),
                List.of(),
                List.of(new ColumnSchema("id", "INT"))
        );
    }
}
