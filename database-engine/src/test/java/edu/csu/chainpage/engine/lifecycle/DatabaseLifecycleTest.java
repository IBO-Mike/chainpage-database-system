package edu.csu.chainpage.engine.lifecycle;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.TableSchema;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseLifecycleTest {

    @Test
    void startsLoadsCatalogAndShutsDownAfterFlush() {
        FakePageStorageClient storage = new FakePageStorageClient();
        CatalogSnapshot snapshot = new CatalogSnapshot(List.of(
                new TableSchema("student", List.of())
        ));
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(snapshot),
                storage
        );

        var startup = lifecycle.startup("req-1");
        var shutdown = lifecycle.shutdown("req-2");

        assertTrue(startup.isOk());
        assertTrue(startup.data().isReady());
        assertTrue(shutdown.isOk());
        assertTrue(shutdown.data().isClosed());
        assertFalse(lifecycle.isReady());
        assertTrue(lifecycle.requireReady("req-3").error() != null);
    }

    @Test
    void remainsNotReadyWhenCatalogLoadFails() {
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.fail(new DbError(
                        requestId, null, "CATALOG", "LOAD_FAILED", "目录加载失败", null, null, null)),
                new FakePageStorageClient()
        );

        var startup = lifecycle.startup("req-1");

        assertFalse(startup.isOk());
        assertFalse(lifecycle.isReady());
    }
}
