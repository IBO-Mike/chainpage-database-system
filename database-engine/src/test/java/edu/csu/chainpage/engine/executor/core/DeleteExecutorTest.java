package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证删除全部记录、只删除谓词匹配记录以及RowId范围精确性
class DeleteExecutorTest {

    @Test
    void deletesOnlyRowsMatchingPredicate() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        fixture.createStudentTable();
        DeleteExecutor executor = new DeleteExecutor(fixture.storageEngine, fixture.catalogManager);
        var result = executor.execute("delete-one", deletePlan(Map.of(
                "kind", "BinaryExpr", "operator", "=",
                "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                "right", Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1)
        )));

        assertTrue(result.isOk());
        assertEquals("DELETE", result.data().commandResult().kind());
        assertEquals(1, result.data().commandResult().affectedRows());
        var remaining = fixture.storageEngine.scanRows(
                "scan", ExecutorSupport.toStorageSchema(fixture.contractSchema())
        );
        assertEquals(List.of(2), remaining.data().rows().stream()
                .map(row -> row.values().valueOf("id")).toList());
    }

    @Test
    void deletesAllRowsWithoutPredicateAndReportsMissingTable() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        fixture.createStudentTable();
        DeleteExecutor executor = new DeleteExecutor(fixture.storageEngine, fixture.catalogManager);
        var all = executor.execute("delete-all", deletePlan(null));
        assertTrue(all.isOk());
        assertEquals(2, all.data().commandResult().affectedRows());
        assertEquals(0, fixture.storageEngine.scanRows(
                "scan", ExecutorSupport.toStorageSchema(fixture.contractSchema())
        ).data().size());

        Map<String, Object> missingFields = new java.util.LinkedHashMap<>();
        missingFields.put("kind", "Delete");
        missingFields.put("table", "missing");
        missingFields.put("predicate", null);
        var missing = executor.execute("delete-missing", new JsonPlanNode(
                "Delete", missingFields, List.of(), List.of()
        ));
        assertFalse(missing.isOk());
        assertEquals("EXECUTOR_TABLE_NOT_FOUND", missing.error().getCode());
    }

    // 构造带有可选谓词的删除计划；null谓词表示删除全部记录
    private JsonPlanNode deletePlan(Object predicate) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("kind", "Delete");
        fields.put("table", "student");
        fields.put("predicate", predicate);
        return new JsonPlanNode("Delete", fields, List.of(), List.of());
    }
}
