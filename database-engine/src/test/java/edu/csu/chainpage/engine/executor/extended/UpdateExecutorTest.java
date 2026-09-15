package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.storage.RowId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证Update执行器的谓词、表达式赋值和类型错误处理
class UpdateExecutorTest {

    @Test
    void updatesOnlyMatchingRowAndKeepsItsRowId() {
        ExtendedExecutorTestSupport fixture = new ExtendedExecutorTestSupport();
        fixture.initializeStudentTable();
        RowId originalRowId = fixture.scanStudentRows().rows().get(1).rowId();
        UpdateExecutor executor = new UpdateExecutor(fixture.storageEngine, fixture.catalogManager);
        var plan = fixture.updatePlan(
                List.of(
                        fixture.assignment("id", fixture.binary(
                                "+",
                                fixture.identifier("id"),
                                fixture.literal("INT", 10)
                        )),
                        fixture.assignment("name", fixture.literal("VARCHAR", "Bobby"))
                ),
                fixture.binary("=", fixture.identifier("id"), fixture.literal("INT", 2))
        );

        var result = executor.execute("req-update-one", plan);
        var rows = fixture.scanStudentRows();

        assertTrue(result.isOk());
        ExecutionValue value = result.data();
        assertEquals("UPDATE", value.commandResult().kind());
        assertEquals(1, value.commandResult().affectedRows());
        assertEquals(List.of(1, 12, 3), rows.rows().stream()
                .map(row -> row.values().valueOf("id"))
                .toList());
        assertEquals("Bobby", rows.rows().get(1).values().valueOf("name"));
        assertEquals(originalRowId, rows.rows().get(1).rowId());
    }

    @Test
    void updatesAllRowsWhenPredicateIsNull() {
        ExtendedExecutorTestSupport fixture = new ExtendedExecutorTestSupport();
        fixture.initializeStudentTable();
        UpdateExecutor executor = new UpdateExecutor(fixture.storageEngine, fixture.catalogManager);
        var plan = fixture.updatePlan(
                List.of(fixture.assignment("name", fixture.literal("VARCHAR", "updated"))),
                null
        );

        var result = executor.execute("req-update-all", plan);

        assertTrue(result.isOk());
        assertEquals(3, result.data().commandResult().affectedRows());
        assertEquals(List.of("updated", "updated", "updated"), fixture.scanStudentRows().rows().stream()
                .map(row -> row.values().valueOf("name"))
                .toList());
    }

    @Test
    void rejectsAssignmentTypeMismatchBeforeChangingRows() {
        ExtendedExecutorTestSupport fixture = new ExtendedExecutorTestSupport();
        fixture.initializeStudentTable();
        UpdateExecutor executor = new UpdateExecutor(fixture.storageEngine, fixture.catalogManager);
        var plan = fixture.updatePlan(
                List.of(fixture.assignment("id", fixture.literal("VARCHAR", "wrong"))),
                null
        );

        var result = executor.execute("req-update-type", plan);

        assertFalse(result.isOk());
        assertEquals("ROW_TYPE_MISMATCH", result.error().getCode());
        assertEquals(List.of(1, 2, 3), fixture.scanStudentRows().rows().stream()
                .map(row -> row.values().valueOf("id"))
                .toList());
    }
}
