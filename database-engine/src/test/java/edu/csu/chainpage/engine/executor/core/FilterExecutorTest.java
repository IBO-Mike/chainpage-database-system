package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证Filter消费子计划行集并处理真、假和非法WHERE表达式
class FilterExecutorTest {

    @Test
    void filtersRowsUsingDispatcherChildResult() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        fixture.createStudentTable();
        PlanDispatcher dispatcher = dispatcher(fixture);
        FilterExecutor executor = new FilterExecutor(dispatcher);
        JsonPlanNode plan = filterPlan(
                Map.of("kind", "BinaryExpr", "operator", ">",
                        "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                        "right", Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1)),
                fixture.scanPlan()
        );

        var result = dispatcher.execute("filter", plan);

        assertTrue(result.isOk());
        assertTrue(result.data().isRowSet());
        assertEquals(1, result.data().rowSet().size());
        assertEquals(2, result.data().rowSet().rows().get(0).values().valueOf("id"));
    }

    @Test
    void returnsEmptyForFalseAndErrorForInvalidPredicate() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        fixture.createStudentTable();
        FilterExecutor executor = new FilterExecutor(new PlanDispatcher());
        var input = new SeqScanExecutor(fixture.storageEngine, fixture.catalogManager)
                .execute("scan", fixture.scanPlan()).data().rowSet();

        var falseResult = executor.filter(input, Map.of(
                "kind", "LiteralExpr", "literalType", "BOOL", "value", false
        ));
        var invalidResult = executor.filter(input, Map.of(
                "kind", "IdentifierExpr", "name", "missing"
        ));

        assertTrue(falseResult.isOk());
        assertEquals(0, falseResult.data().size());
        assertFalse(invalidResult.isOk());
        assertEquals("EXECUTOR_PREDICATE_ERROR", invalidResult.error().getCode());
    }

    // 创建注册了顺序扫描和过滤执行器的计划分派器
    private PlanDispatcher dispatcher(CoreExecutorTestSupport fixture) {
        PlanDispatcher dispatcher = new PlanDispatcher();
        dispatcher.register(new SeqScanExecutor(fixture.storageEngine, fixture.catalogManager));
        dispatcher.register(new FilterExecutor(dispatcher));
        return dispatcher;
    }

    // 构造过滤计划
    private JsonPlanNode filterPlan(Object predicate, JsonPlanNode child) {
        return new JsonPlanNode(
                "Filter",
                Map.of("kind", "Filter", "predicate", predicate),
                List.of(child),
                child.schema()
        );
    }
}
