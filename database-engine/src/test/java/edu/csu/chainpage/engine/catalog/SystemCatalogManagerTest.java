package edu.csu.chainpage.engine.catalog;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证系统目录内存视图与持久化仓库之间的更新顺序
class SystemCatalogManagerTest {

    @Test
    void initializesCreatesQueriesAndRejectsDuplicateTable() {
        FakePageStorageClient client = new FakePageStorageClient();
        SystemCatalogManager manager = manager(client);
        TableSchema schema = schema();

        assertTrue(manager.initialize("req-1").isOk());
        var created = manager.createTable("req-2", schema);
        var found = manager.getTable("req-3", "STUDENT");
        var duplicate = manager.createTable("req-4", schema);

        assertTrue(created.isOk());
        assertTrue(found.isOk());
        assertTrue(found.data().isPresent());
        assertFalse(duplicate.isOk());
        assertEquals("SYSTEM_CATALOG_TABLE_EXISTS", duplicate.error().getCode());
        assertTrue(manager.containsTable("student"));
    }

    @Test
    void failedRepositorySaveDoesNotUpdateMemoryView() {
        FakePageStorageClient client = new FakePageStorageClient();
        SystemCatalogManager manager = manager(client);
        manager.initialize("req-1");
        client.failNext(
                FakePageStorageClient.WRITE_PAGE,
                new DbError("req-2", null, "STORAGE", "FILE_IO_ERROR", "写入失败", null, null, 0)
        );

        var result = manager.createTable("req-2", schema());

        assertFalse(result.isOk());
        assertFalse(manager.containsTable("student"));
        assertTrue(manager.snapshot().getTables().isEmpty());
    }

    @Test
    void restoresDirectoryWithANewEngineAgainstTheSamePageStorage() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageEngine firstEngine = new StorageEngine(client);
        StorageCatalogRepository firstRepository = new StorageCatalogRepository(firstEngine);
        SystemCatalogManager firstManager = new SystemCatalogManager(firstRepository);
        firstManager.initialize("req-1");
        firstManager.createTable("req-2", schema());

        StorageEngine secondEngine = new StorageEngine(client);
        SystemCatalogManager secondManager = new SystemCatalogManager(
                new StorageCatalogRepository(secondEngine)
        );
        var startup = secondManager.initialize("req-3");
        var restored = secondManager.getTable("req-4", "student");

        assertTrue(startup.isOk());
        assertTrue(restored.isOk());
        assertTrue(restored.data().isPresent());
        assertEquals(2, restored.data().get().getColumns().size());
    }

    @Test
    void removesDirectoryEntryOnlyAfterRepositorySucceeds() {
        FakePageStorageClient client = new FakePageStorageClient();
        SystemCatalogManager manager = manager(client);
        manager.initialize("req-1");
        manager.createTable("req-2", schema());

        assertTrue(manager.removeTable("req-3", "STUDENT").isOk());
        assertFalse(manager.containsTable("student"));
        assertTrue(manager.snapshot().getTables().isEmpty());
    }

    private SystemCatalogManager manager(FakePageStorageClient client) {
        return new SystemCatalogManager(new StorageCatalogRepository(new StorageEngine(client)));
    }

    private TableSchema schema() {
        return new TableSchema(
                "Student",
                List.of(
                        new ColumnSchema("ID", "INT"),
                        new ColumnSchema("Name", "VARCHAR")
                )
        );
    }
}
