package edu.csu.chainpage.engine.storage;

import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证按RowId逻辑删除，并确保其他记录不受影响
class StorageEngineDeleteTest {

    @Test
    void deletesOnlyRequestedRowsAndScanSkipsDeletedSlots() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(client);
        TableSchema schema = schema();
        engine.createTableStorage("req-1", schema);
        RowId first = engine.insertRow("req-2", "student", row(1)).data();
        RowId second = engine.insertRow("req-3", "student", row(2)).data();
        RowId third = engine.insertRow("req-4", "student", row(3)).data();

        var deleted = engine.deleteRows("req-5", "student", List.of(second));
        var scanned = engine.scanRows("req-6", schema);
        var repeated = engine.deleteRows("req-7", "student", List.of(second));

        assertEquals(1, deleted.data());
        assertEquals(2, scanned.data().size());
        assertEquals(List.of(first, third), scanned.data().rows().stream()
                .map(InternalRow::rowId)
                .toList());
        assertEquals(0, repeated.data());
        assertTrue(client.callCount(FakePageStorageClient.WRITE_PAGE) >= 4);
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
