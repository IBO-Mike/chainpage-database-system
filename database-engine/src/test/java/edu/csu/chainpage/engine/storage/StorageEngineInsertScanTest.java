package edu.csu.chainpage.engine.storage;

import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证已有页写入、新页分配和跨页顺序扫描
class StorageEngineInsertScanTest {

    @Test
    void insertsRowsAcrossPagesAndScansAllLiveRows() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(client);
        TableSchema schema = schema();
        engine.createTableStorage("req-1", schema);

        RowId first = engine.insertRow("req-2", "STUDENT", row(1)).data();
        RowId second = engine.insertRow("req-3", "student", row(2)).data();
        RowId third = engine.insertRow("req-4", "student", row(3)).data();
        var scanned = engine.scanRows("req-5", schema);

        assertEquals(0, first.pageId());
        assertEquals(0, second.pageId());
        assertEquals(1, third.pageId());
        assertTrue(scanned.isOk());
        assertEquals(3, scanned.data().size());
        assertEquals(List.of(1, 2, 3), scanned.data().rows().stream()
                .map(internalRow -> internalRow.values().valueOf("id"))
                .toList());
        assertEquals(2, client.callCount(FakePageStorageClient.ALLOCATE_PAGE_FOR_TABLE));
    }

    private TableSchema schema() {
        return new TableSchema(
                "student",
                List.of(new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"))
        );
    }

    private Row row(int id) {
        return new Row(Map.of("id", id, "name", "x".repeat(2000)));
    }
}
