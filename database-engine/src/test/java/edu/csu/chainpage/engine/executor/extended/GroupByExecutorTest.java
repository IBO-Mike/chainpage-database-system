package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.storage.ColumnSchema;
import edu.csu.chainpage.engine.storage.RowSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证GroupBy执行器的COUNT、SUM、空输入和错误处理
class GroupByExecutorTest {

    private final GroupByExecutor executor = new GroupByExecutor();

    @Test
    void groupsRowsAndCalculatesCountAndSum() {
        RowSet input = ExtendedExecutorTestSupport.rowSet(
                List.of(
                        new ColumnSchema("category", "VARCHAR"),
                        new ColumnSchema("amount", "INT")
                ),
                List.of(
                        Map.of("category", "A", "amount", 10),
                        Map.of("category", "A", "amount", 20),
                        Map.of("category", "B", "amount", 5)
                )
        );

        var result = executor.group(
                input,
                List.of("category"),
                List.of(
                        new AggregateSpec("COUNT", "*", "row_count"),
                        new AggregateSpec("SUM", "amount", "total")
                )
        );

        assertTrue(result.isOk());
        assertEquals(List.of("category", "row_count", "total"), result.data().schema().stream()
                .map(ColumnSchema::getName)
                .toList());
        assertEquals(List.of("A", "B"), result.data().rows().stream()
                .map(row -> row.values().valueOf("category"))
                .toList());
        assertEquals(List.of(2, 1), result.data().rows().stream()
                .map(row -> row.values().valueOf("row_count"))
                .toList());
        assertEquals(List.of(30, 5), result.data().rows().stream()
                .map(row -> row.values().valueOf("total"))
                .toList());
        assertNull(result.data().rows().get(0).rowId());
    }

    @Test
    void returnsOneGlobalAggregateRowForEmptyInput() {
        RowSet empty = new RowSet(List.of(new ColumnSchema("amount", "INT")), List.of());

        var result = executor.group(
                empty,
                List.of(),
                List.of(
                        new AggregateSpec("COUNT", "*", "row_count"),
                        new AggregateSpec("SUM", "amount", "total")
                )
        );

        assertTrue(result.isOk());
        assertEquals(1, result.data().size());
        assertEquals(0, result.data().rows().get(0).values().valueOf("row_count"));
        assertEquals(0, result.data().rows().get(0).values().valueOf("total"));
    }

    @Test
    void rejectsMissingAggregateColumnAndWrongSumType() {
        RowSet input = ExtendedExecutorTestSupport.rowSet(
                List.of(new ColumnSchema("category", "VARCHAR")),
                List.of(Map.of("category", "A"))
        );

        var missing = executor.group(
                input,
                List.of(),
                List.of(new AggregateSpec("COUNT", "missing", "count_value"))
        );
        var wrongType = executor.group(
                input,
                List.of(),
                List.of(new AggregateSpec("SUM", "category", "total"))
        );

        assertFalse(missing.isOk());
        assertEquals("EXECUTOR_GROUP_BY_COLUMN_NOT_FOUND", missing.error().getCode());
        assertFalse(wrongType.isOk());
        assertEquals("EXECUTOR_GROUP_BY_TYPE_MISMATCH", wrongType.error().getCode());
    }
}
