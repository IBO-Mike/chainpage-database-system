package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证建表执行顺序、重复表和目录失败补偿
class CreateTableExecutorTest {

    @Test
    void createsStorageThenCatalogAndReturnsCreateResult() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        CreateTableExecutor executor = new CreateTableExecutor(
                fixture.storageEngine, fixture.catalogManager
        );

        var result = executor.execute("req-1", fixture.createTablePlan());

        assertTrue(result.isOk());
        assertEquals("CREATE", result.data().commandResult().kind());
        assertEquals(2, fixture.pageStorageClient.callCount(FakePageStorageClient.CREATE_TABLE_PAGES));
        assertTrue(fixture.catalogManager.containsTable("student"));
    }

    @Test
    void rejectsDuplicateTableAndStorageFailure() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        CreateTableExecutor executor = new CreateTableExecutor(
                fixture.storageEngine, fixture.catalogManager
        );
        assertTrue(executor.execute("req-1", fixture.createTablePlan()).isOk());

        var duplicate = executor.execute("req-2", fixture.createTablePlan());
        assertFalse(duplicate.isOk());
        assertEquals("TABLE_ALREADY_EXISTS", duplicate.error().getCode());

        CoreExecutorTestSupport failedFixture = new CoreExecutorTestSupport();
        failedFixture.initialize();
        failedFixture.pageStorageClient.failNext(
                FakePageStorageClient.CREATE_TABLE_PAGES,
                new DbError("req-3", null, "STORAGE", "FILE_IO_ERROR", "创建失败", null, null, null)
        );
        var failed = new CreateTableExecutor(
                failedFixture.storageEngine, failedFixture.catalogManager
        ).execute("req-3", failedFixture.createTablePlan());
        assertFalse(failed.isOk());
        assertEquals("FILE_IO_ERROR", failed.error().getCode());
    }

    @Test
    void compensatesStorageWhenCatalogPersistenceFails() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        fixture.pageStorageClient.failNext(
                FakePageStorageClient.WRITE_PAGE,
                new DbError("req-4", null, "STORAGE", "FILE_IO_ERROR", "目录写入失败", null, null, null)
        );
        CreateTableExecutor executor = new CreateTableExecutor(
                fixture.storageEngine, fixture.catalogManager
        );

        var result = executor.execute("req-4", fixture.createTablePlan());

        assertFalse(result.isOk());
        assertEquals("FILE_IO_ERROR", result.error().getCode());
        assertEquals(1, fixture.pageStorageClient.callCount(FakePageStorageClient.DROP_TABLE_PAGES));
        assertFalse(fixture.catalogManager.containsTable("student"));
    }
}
