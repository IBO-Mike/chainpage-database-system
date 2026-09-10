package edu.csu.chainpage.engine.catalog;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证目录表的初始化、保存、加载、删除和失败传播
class StorageCatalogRepositoryTest {

    @Test
    void initializesReservedCatalogTableAndLoadsEmptyDirectory() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageCatalogRepository repository = repository(client);

        assertTrue(repository.initialize("req-1").isOk());
        var loaded = repository.load("req-2");

        assertTrue(loaded.isOk());
        assertTrue(loaded.data().isEmpty());
        assertEquals(1, client.callCount(FakePageStorageClient.CREATE_TABLE_PAGES));
    }

    @Test
    void savesAndLoadsTableSchemaThroughStorageEngine() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageCatalogRepository repository = repository(client);
        repository.initialize("req-1");
        TableSchema schema = schema();

        assertTrue(repository.save("req-2", schema).isOk());
        var loaded = repository.load("req-3");

        assertTrue(loaded.isOk());
        assertEquals(1, loaded.data().size());
        assertEquals("student", loaded.data().get(0).getName());
        assertEquals("VARCHAR", loaded.data().get(0).getColumns().get(1).getDataType());
    }

    @Test
    void convertsCatalogRowInBothDirections() {
        StorageCatalogRepository repository = repository(new FakePageStorageClient());
        TableSchema schema = schema();

        Row row = repository.toCatalogRow(schema);
        var restored = repository.fromCatalogRow(row);

        assertTrue(restored.isOk());
        assertEquals("student", restored.data().getName());
        assertEquals(schema.getColumns().size(), restored.data().getColumns().size());
        assertEquals("id", restored.data().getColumns().get(0).getName());
    }

    @Test
    void removesOnlyTheRequestedCatalogRow() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageCatalogRepository repository = repository(client);
        repository.initialize("req-1");
        repository.save("req-2", schema());

        assertTrue(repository.remove("req-3", "STUDENT").isOk());
        assertTrue(repository.load("req-4").data().isEmpty());
    }

    @Test
    void doesNotPersistSchemaWhenCatalogPageWriteFails() {
        FakePageStorageClient client = new FakePageStorageClient();
        StorageCatalogRepository repository = repository(client);
        repository.initialize("req-1");
        client.failNext(
                FakePageStorageClient.WRITE_PAGE,
                new DbError("req-2", null, "STORAGE", "FILE_IO_ERROR", "写入失败", null, null, 0)
        );

        var saved = repository.save("req-2", schema());
        var loaded = repository.load("req-3");

        assertFalse(saved.isOk());
        assertEquals("FILE_IO_ERROR", saved.error().getCode());
        assertTrue(loaded.isOk());
        assertTrue(loaded.data().isEmpty());
    }

    private StorageCatalogRepository repository(FakePageStorageClient client) {
        return new StorageCatalogRepository(new StorageEngine(client));
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
