package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证执行器登记、计划分派和错误处理
class PlanDispatcherTest {

    @Test
    void dispatchesPlanToRegisteredExecutor() {
        PlanDispatcher dispatcher = new PlanDispatcher();
        dispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                return DbResult.ok(ExecutionValue.command(CommandResult.insert()));
            }

            @Override
            public boolean supports(String kind) {
                return "Insert".equals(kind);
            }
        });

        PlanNode plan = new PlanParser().parse(insertPlan()).data();
        var result = dispatcher.execute("req-1", plan);

        assertTrue(result.isOk());
        assertEquals("INSERT", result.data().commandResult().getKind());
    }

    @Test
    void returnsUnsupportedErrorForUnknownPlan() {
        PlanDispatcher dispatcher = new PlanDispatcher();
        PlanNode unknown = new JsonPlanNode(
                "FutureNode",
                Map.of("kind", "FutureNode"),
                List.of(),
                List.of()
        );

        var result = dispatcher.execute("req-1", unknown);

        assertFalse(result.isOk());
        assertEquals("EXECUTOR_UNSUPPORTED_PLAN", result.error().getCode());
        assertEquals("req-1", result.error().getRequestId());
    }

    @Test
    void returnsUnsupportedErrorWhenKnownKindHasNoExecutor() {
        PlanNode plan = new PlanParser().parse(insertPlan()).data();

        var result = new PlanDispatcher().execute("req-1", plan);

        assertFalse(result.isOk());
        assertEquals("EXECUTOR_UNSUPPORTED_PLAN", result.error().getCode());
    }

    @Test
    void preservesExecutorFailureObject() {
        DbError error = DbError.executor("req-1", null, "EXECUTOR_CUSTOM_ERROR", "执行器失败");
        PlanDispatcher dispatcher = new PlanDispatcher();
        dispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                return DbResult.fail(error);
            }

            @Override
            public boolean supports(String kind) {
                return "Insert".equals(kind);
            }
        });

        var result = dispatcher.execute("req-1", new PlanParser().parse(insertPlan()).data());

        assertFalse(result.isOk());
        assertSame(error, result.error());
    }

    @Test
    void validatesChildCountBeforeExecution() {
        PlanDispatcher dispatcher = new PlanDispatcher();
        PlanNode invalid = new JsonPlanNode(
                "Filter",
                Map.of(
                        "kind", "Filter",
                        "predicate", Map.of("kind", "LiteralExpr", "literalType", "BOOL", "value", true)
                ),
                List.of(),
                List.of()
        );

        var result = dispatcher.execute("req-1", invalid);

        assertFalse(result.isOk());
        assertEquals("EXECUTOR_INVALID_PLAN", result.error().getCode());
    }

    private Map<String, Object> insertPlan() {
        return Map.of(
                "kind", "Insert",
                "table", "student",
                "columns", List.of("id"),
                "values", List.of(Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1)),
                "children", List.of(),
                "schema", List.of()
        );
    }
}
