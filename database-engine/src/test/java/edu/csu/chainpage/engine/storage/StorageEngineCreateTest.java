package edu.csu.chainpage.engine.storage;

import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证表页映射的创建和重复创建保护
class StorageEngineCreateTest {

    @Test
    void createsEmptyTableStorageWithoutAllocatingDataPage() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(client);
        TableSchema schema = schema();

        var result = engine.createTableStorage("req-1", schema);

        assertTrue(result.isOk());
        assertEquals("student", result.data().getTable());
        assertTrue(result.data().getPageIds().isEmpty());
        assertEquals(0, client.callCount(FakePageStorageClient.ALLOCATE_PAGE_FOR_TABLE));
    }

    @Test
    void rejectsDuplicateTableInTheSameEngine() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageEngine engine = new StorageEngine(client);
        TableSchema schema = schema();

        engine.createTableStorage("req-1", schema);
        var duplicate = engine.createTableStorage("req-2", schema);

        assertFalse(duplicate.isOk());
        assertEquals("TABLE_ALREADY_EXISTS", duplicate.error().getCode());
        assertEquals(1, client.callCount(FakePageStorageClient.CREATE_TABLE_PAGES));
    }

    private TableSchema schema() {
        return new TableSchema(
                "student",
                List.of(new ColumnSchema("id", "INT"), new ColumnSchema("name", "VARCHAR"))
        );
    }
}
