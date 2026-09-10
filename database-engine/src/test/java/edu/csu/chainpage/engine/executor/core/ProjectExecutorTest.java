package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证Project的SELECT *、显式列顺序和未知列错误
class ProjectExecutorTest {

    @Test
    void projectsAllColumnsAndExplicitOrder() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        fixture.createStudentTable();
        PlanDispatcher dispatcher = new PlanDispatcher();
        dispatcher.register(new SeqScanExecutor(fixture.storageEngine, fixture.catalogManager));
        dispatcher.register(new ProjectExecutor(dispatcher));
        ProjectExecutor executor = new ProjectExecutor(dispatcher);

        var selectAll = dispatcher.execute("select-all", projectPlan(List.of("*"), fixture.scanPlan()));
        var selectOrder = dispatcher.execute("select-order", projectPlan(List.of("name", "id"), fixture.scanPlan()));

        assertTrue(selectAll.isOk());
        assertEquals(List.of("id", "name"), selectAll.data().commandResult().columns());
        assertEquals(List.of(1, "Alice"), selectAll.data().commandResult().rows().get(0));
        assertTrue(selectOrder.isOk());
        assertEquals(List.of("name", "id"), selectOrder.data().commandResult().columns());
        assertEquals(List.of("Alice", 1), selectOrder.data().commandResult().rows().get(0));

        var direct = executor.resolveColumns(projectPlan(List.of("id"), fixture.scanPlan()),
                new SeqScanExecutor(fixture.storageEngine, fixture.catalogManager)
                        .execute("scan", fixture.scanPlan()).data().rowSet());
        assertEquals(List.of("id"), direct.data());
    }

    @Test
    void rejectsUnknownAndMixedWildcardColumns() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        fixture.createStudentTable();
        ProjectExecutor executor = new ProjectExecutor(new PlanDispatcher());
        var input = new SeqScanExecutor(fixture.storageEngine, fixture.catalogManager)
                .execute("scan", fixture.scanPlan()).data().rowSet();

        var unknown = executor.resolveColumns(projectPlan(List.of("missing"), fixture.scanPlan()), input);
        var mixed = executor.resolveColumns(projectPlan(List.of("*", "id"), fixture.scanPlan()), input);

        assertFalse(unknown.isOk());
        assertEquals("EXECUTOR_PROJECT_ERROR", unknown.error().getCode());
        assertFalse(mixed.isOk());
    }

    // 构造投影计划
    private JsonPlanNode projectPlan(List<String> columns, JsonPlanNode child) {
        return new JsonPlanNode(
                "Project",
                Map.of("kind", "Project", "columns", columns),
                List.of(child),
                child.schema()
        );
    }
}
