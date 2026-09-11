package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.api.DatabaseApi;
import edu.csu.chainpage.engine.api.DatabaseRequest;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.lifecycle.RecoveryService;
import edu.csu.chainpage.engine.storage.StorageEngine;
import edu.csu.chainpage.engine.support.CoreEngineFixture;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证数据库关闭后能够使用全新引擎对象恢复目录和数据
class RestartRecoveryTest {

    @Test
    void createInsertShutdownStartupSelect() {
        FakePageStorageClient persistentPages = new FakePageStorageClient();
        CoreEngineFixture firstProcess = new CoreEngineFixture(persistentPages);
        var written = firstProcess.execute(
                firstProcess.createTablePlan(),
                firstProcess.insertPlan(7, "Alice")
        );
        assertTrue(written.isOk());
        assertTrue(firstProcess.lifecycle.shutdown("req-shutdown").isOk());

        CoreEngineFixture restartedProcess = new CoreEngineFixture(persistentPages);
        var selected = restartedProcess.execute(restartedProcess.projectPlan(
                List.of("*"),
                restartedProcess.scanPlan()
        ));

        assertTrue(selected.isOk());
        StatementExecutionResult statement =
                (StatementExecutionResult) selected.data().getResults().get(0);
        CommandResult command = (CommandResult) statement.getResult();
        assertEquals(List.of(List.of(7, "Alice")), command.rows());
        assertTrue(restartedProcess.catalogManager.containsTable("student"));
    }

    @Test
    void refusesSqlUntilStartupSucceeds() {
        FakePageStorageClient pages = new FakePageStorageClient();
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        RecoveryService recovery = new RecoveryService(
                requestId -> DbResult.fail(new DbError(
                        requestId,
                        null,
                        "CATALOG",
                        "CATALOG_CORRUPTED",
                        "目录损坏",
                        null,
                        null,
                        null
                )),
                new StorageEngine(pages)
        );
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(recovery, pages);
        DatabaseApi api = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler,
                (requestId, statementIndex, plan) -> DbResult.ok(
                        new StatementExecutionResult(statementIndex, "SELECT", plan)
                )
        );

        var startup = lifecycle.startup("req-startup");
        var request = api.handle(
                "req-sql",
                new DatabaseRequest("SELECT * FROM student;", "execute")
        );

        assertFalse(startup.isOk());
        assertFalse(lifecycle.isReady());
        assertFalse(request.isOk());
        assertEquals("DATABASE_NOT_READY", request.error().getCode());
        assertTrue(compiler.receivedRequests().isEmpty());
    }

    @Test
    void shutdownReportsFlushedPages() {
        FakePageStorageClient persistentPages = new FakePageStorageClient();
        CoreEngineFixture fixture = new CoreEngineFixture(persistentPages);
        fixture.execute(fixture.createTablePlan(), fixture.insertPlan(1, "Alice"));

        var shutdown = fixture.lifecycle.shutdown("req-shutdown");

        assertTrue(shutdown.isOk());
        assertTrue(shutdown.data().isClosed());
        assertEquals(2, shutdown.data().getFlushedPages().size());
        assertEquals(1, persistentPages.callCount(FakePageStorageClient.FLUSH_ALL));
        assertFalse(fixture.lifecycle.isReady());
    }
}
