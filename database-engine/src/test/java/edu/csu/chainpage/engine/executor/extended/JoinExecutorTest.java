package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.storage.ColumnSchema;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.RowSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证双子行集连接、输出模式和连接字段错误
class JoinExecutorTest {

    private final JoinExecutor executor = new JoinExecutor();

    @Test
    void joinsMatchingRowsAndBuildsCombinedSchema() {
        RowSet left = ExtendedExecutorTestSupport.rowSet(
                List.of(
                        new ColumnSchema("student_id", "INT"),
                        new ColumnSchema("student_name", "VARCHAR")
                ),
                List.of(
                        Map.of("student_id", 1, "student_name", "Alice"),
                        Map.of("student_id", 2, "student_name", "Bob")
                )
        );
        RowSet right = ExtendedExecutorTestSupport.rowSet(
                List.of(
                        new ColumnSchema("owner_id", "INT"),
                        new ColumnSchema("course", "VARCHAR")
                ),
                List.of(
                        Map.of("owner_id", 1, "course", "Database"),
                        Map.of("owner_id", 1, "course", "Operating Systems"),
                        Map.of("owner_id", 3, "course", "Networks")
                )
        );

        var result = executor.join(left, right, "student_id", "owner_id");

        assertTrue(result.isOk());
        assertEquals(List.of("student_id", "student_name", "owner_id", "course"),
                result.data().schema().stream().map(ColumnSchema::getName).toList());
        assertEquals(List.of("Database", "Operating Systems"), result.data().rows().stream()
                .map(row -> row.values().valueOf("course"))
                .toList());
        assertEquals("Alice", result.data().rows().get(0).values().valueOf("student_name"));
        assertNull(result.data().rows().get(0).rowId());
    }

    @Test
    void rejectsMissingAndMismatchedJoinKeys() {
        RowSet left = ExtendedExecutorTestSupport.rowSet(
                List.of(new ColumnSchema("id", "INT")),
                List.of(Map.of("id", 1))
        );
        RowSet right = ExtendedExecutorTestSupport.rowSet(
                List.of(new ColumnSchema("owner", "VARCHAR")),
                List.of(Map.of("owner", "1"))
        );

        var missing = executor.join(left, right, "missing", "owner");
        var mismatch = executor.join(left, right, "id", "owner");

        assertFalse(missing.isOk());
        assertEquals("EXECUTOR_JOIN_COLUMN_NOT_FOUND", missing.error().getCode());
        assertFalse(mismatch.isOk());
        assertEquals("EXECUTOR_JOIN_TYPE_MISMATCH", mismatch.error().getCode());
    }

    @Test
    void rejectsColumnNameCollisionWhenMergingRows() {
        var result = executor.mergeRows(
                new Row(Map.of("id", 1, "name", "Alice")),
                new Row(Map.of("id", 1, "course", "Database"))
        );

        assertFalse(result.isOk());
        assertEquals("EXECUTOR_JOIN_COLUMN_CONFLICT", result.error().getCode());
    }
}
