package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.storage.ColumnSchema;
import edu.csu.chainpage.engine.storage.RowSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证单键、多键、升降序和非法排序输入
class SortExecutorTest {

    private final SortExecutor executor = new SortExecutor();

    @Test
    void sortsAscendingDescendingAndByMultipleKeys() {
        RowSet input = rows();

        var descending = executor.sort(input, List.of(new SortKey("id", "DESC")));
        var multiple = executor.sort(input, List.of(
                new SortKey("id", "ASC"),
                new SortKey("name", "DESC")
        ));

        assertTrue(descending.isOk());
        assertEquals(List.of(2, 1, 1), descending.data().rows().stream()
                .map(row -> row.values().valueOf("id"))
                .toList());
        assertTrue(multiple.isOk());
        assertEquals(List.of("Cara", "Alice", "Bob"), multiple.data().rows().stream()
                .map(row -> row.values().valueOf("name"))
                .toList());
        assertEquals("Bob", input.rows().get(0).values().valueOf("name"));
    }

    @Test
    void rejectsMissingColumnAndMismatchedValueType() {
        var missing = executor.sort(rows(), List.of(new SortKey("missing", "ASC")));
        RowSet wrongType = ExtendedExecutorTestSupport.rowSet(
                List.of(new ColumnSchema("id", "INT")),
                List.of(Map.of("id", "not-an-int"))
        );
        var mismatch = executor.sort(wrongType, List.of(new SortKey("id", "ASC")));

        assertFalse(missing.isOk());
        assertEquals("EXECUTOR_SORT_COLUMN_NOT_FOUND", missing.error().getCode());
        assertFalse(mismatch.isOk());
        assertEquals("EXECUTOR_SORT_TYPE_MISMATCH", mismatch.error().getCode());
    }

    // 创建包含重复主键值的排序测试行集
    private RowSet rows() {
        return ExtendedExecutorTestSupport.rowSet(
                List.of(
                        new ColumnSchema("id", "INT"),
                        new ColumnSchema("name", "VARCHAR")
                ),
                List.of(
                        Map.of("id", 2, "name", "Bob"),
                        Map.of("id", 1, "name", "Alice"),
                        Map.of("id", 1, "name", "Cara")
                )
        );
    }
}
