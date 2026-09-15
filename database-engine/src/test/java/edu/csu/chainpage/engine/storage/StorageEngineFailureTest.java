package edu.csu.chainpage.engine.storage;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// 验证缺表、记录过大和页式存储失败均能返回明确错误
class StorageEngineFailureTest {

    @Test
    void rejectsInsertIntoMissingTable() {
        StorageEngine engine = new StorageEngine(new FakePageStorageClient());

        var result = engine.insertRow("req-1", "missing", new Row(Map.of("id", 1)));

        assertFalse(result.isOk());
        assertEquals("TABLE_NOT_FOUND", result.error().getCode());
    }

    @Test
    void rejectsRecordThatCannotFitInOnePageBeforeAllocation() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(client);
        TableSchema schema = schema();
        engine.createTableStorage("req-1", schema);

        var result = engine.insertRow(
                "req-2",
                "student",
                new Row(Map.of("id", 1, "name", "x".repeat(4096)))
        );

        assertFalse(result.isOk());
        assertEquals("RECORD_TOO_LARGE", result.error().getCode());
        assertEquals(0, client.callCount(FakePageStorageClient.ALLOCATE_PAGE_FOR_TABLE));
    }

    @Test
    void propagatesPageReadFailureDuringScan() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(client);
        TableSchema schema = schema();
        engine.createTableStorage("req-1", schema);
        engine.insertRow("req-2", "student", row(1));
        client.failNext(
                FakePageStorageClient.GET_PAGE,
                new DbError("req-3", null, "STORAGE", "FILE_IO_ERROR", "读取失败", null, null, 0)
        );

        var result = engine.scanRows("req-3", schema);

        assertFalse(result.isOk());
        assertEquals("FILE_IO_ERROR", result.error().getCode());
        assertEquals(0, result.error().getPageId());
    }

    @Test
    void propagatesPageWriteFailureDuringInsert() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(client);
        TableSchema schema = schema();
        engine.createTableStorage("req-1", schema);
        client.failNext(
                FakePageStorageClient.WRITE_PAGE,
                new DbError("req-2", null, "STORAGE", "FILE_IO_ERROR", "写入失败", null, null, 0)
        );

        var result = engine.insertRow("req-2", "student", row(1));

        assertFalse(result.isOk());
        assertEquals("FILE_IO_ERROR", result.error().getCode());
        assertEquals(0, result.error().getPageId());
    }

    private TableSchema schema() {
        return new TableSchema(
                "student",
                List.of(new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"))
        );
    }

    private Row row(int id) {
        return new Row(Map.of("id", id, "name", "student-" + id));
    }
}
