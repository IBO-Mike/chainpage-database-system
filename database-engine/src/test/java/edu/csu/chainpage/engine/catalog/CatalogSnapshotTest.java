package edu.csu.chainpage.engine.catalog;

import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证提供给编译器的目录快照是只读且与管理器视图隔离的
class CatalogSnapshotTest {

    @Test
    void snapshotContainsTablesAndDoesNotExposeMutableList() {
        SystemCatalogManager manager = new SystemCatalogManager(
                new StorageCatalogRepository(new StorageEngine(new FakePageStorageClient()))
        );
        manager.initialize("req-1");
        manager.createTable("req-2", new TableSchema(
                "student",
                List.of(new ColumnSchema("id", "INT"))
        ));

        CatalogSnapshot snapshot = manager.snapshot();

        assertEquals(1, snapshot.getTables().size());
        assertThrows(UnsupportedOperationException.class, () ->
                snapshot.getTables().add(new TableSchema("other", List.of()))
        );
    }

    @Test
    void oldSnapshotIsNotChangedByLaterCatalogUpdate() {
        SystemCatalogManager manager = new SystemCatalogManager(
                new StorageCatalogRepository(new StorageEngine(new FakePageStorageClient()))
        );
        manager.initialize("req-1");
        manager.createTable("req-2", new TableSchema(
                "student",
                List.of(new ColumnSchema("id", "INT"))
        ));
        CatalogSnapshot first = manager.snapshot();
        manager.createTable("req-3", new TableSchema(
                "course",
                List.of(new ColumnSchema("id", "INT"))
        ));

        assertEquals(1, first.getTables().size());
        assertEquals("student", first.getTables().get(0).getName());
        assertTrue(manager.snapshot().getTables().size() == 2);
    }
}
