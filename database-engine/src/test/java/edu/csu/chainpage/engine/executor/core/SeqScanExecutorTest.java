package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.executor.ExecutionValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证顺序扫描返回完整内部行集以及缺表错误
class SeqScanExecutorTest {

    @Test
    void scansAllRowsFromStorage() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        fixture.createStudentTable();
        SeqScanExecutor executor = new SeqScanExecutor(fixture.storageEngine, fixture.catalogManager);

        var result = executor.execute("scan", fixture.scanPlan());

        assertTrue(result.isOk());
        assertTrue(result.data().isRowSet());
        assertEquals(2, result.data().rowSet().size());
        assertEquals(1, result.data().rowSet().rows().get(0).values().valueOf("id"));
        assertEquals("Bob", result.data().rowSet().rows().get(1).values().valueOf("name"));
    }

    @Test
    void reportsMissingTable() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        SeqScanExecutor executor = new SeqScanExecutor(fixture.storageEngine, fixture.catalogManager);

        var result = executor.execute("scan", new edu.csu.chainpage.engine.plan.JsonPlanNode(
                "SeqScan", java.util.Map.of("kind", "SeqScan", "table", "missing"),
                java.util.List.of(), java.util.List.of()
        ));

        assertFalse(result.isOk());
        assertEquals("EXECUTOR_TABLE_NOT_FOUND", result.error().getCode());
    }
}
