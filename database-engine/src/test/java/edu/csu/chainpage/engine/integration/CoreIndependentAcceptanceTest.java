package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.support.CoreEngineFixture;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证database-engine只依赖公开编译器和页式存储契约即可独立验收
class CoreIndependentAcceptanceTest {

    @Test
    void runsWithFakeCompilerAndFakePageStorageOnly() {
        CoreEngineFixture fixture = new CoreEngineFixture();

        var result = fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(1, "Alice"),
                fixture.projectPlan(List.of("id", "name"), fixture.scanPlan())
        );

        assertTrue(result.isOk());
        assertEquals(3, result.data().getResults().size());
        assertTrue(fixture.compilerClient.receivedRequests().size() >= 1);
        assertTrue(fixture.pageStorageClient.callCount(FakePageStorageClient.CREATE_TABLE_PAGES) >= 2);
    }

    @Test
    void rejectsDuplicateTable() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        assertTrue(fixture.execute(fixture.createTablePlan()).isOk());

        var duplicate = fixture.execute(fixture.createTablePlan());

        assertTrue(duplicate.isOk());
        assertEquals("TABLE_ALREADY_EXISTS", duplicate.data().getError().getCode());
        assertFalse(fixture.catalogManager.snapshot().getTables().isEmpty());
    }

    @Test
    void handlesBatchInsertAndScanAcrossPages() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        String largeName = "x".repeat(2000);

        var result = fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(1, largeName),
                fixture.insertPlan(2, largeName),
                fixture.insertPlan(3, largeName),
                fixture.projectPlan(List.of("id"), fixture.scanPlan())
        );

        assertTrue(result.isOk());
        var rows = ((edu.csu.chainpage.engine.executor.CommandResult)
                ((edu.csu.chainpage.engine.api.StatementExecutionResult)
                        result.data().getResults().get(4)).getResult()).rows();
        assertEquals(List.of(List.of(1), List.of(2), List.of(3)), rows);
        assertTrue(fixture.pageStorageClient.callCount(FakePageStorageClient.ALLOCATE_PAGE_FOR_TABLE) >= 3);
    }
}
