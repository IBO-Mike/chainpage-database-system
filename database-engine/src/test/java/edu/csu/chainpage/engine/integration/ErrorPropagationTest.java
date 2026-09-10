package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.support.CoreEngineFixture;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证编译器错误、存储错误和实际删除数量在入口处的传递
class ErrorPropagationTest {

    @Test
    void preservesCompilerStageAndLocation() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        fixture.compilerClient.enqueueFailure(new DbError(
                "req-parser", null, "PARSER", "PARSER_MISSING_SEMICOLON",
                "缺少分号", 3, 12, null
        ));

        var result = fixture.databaseApi.handle(
                "req-parser",
                new edu.csu.chainpage.engine.api.DatabaseRequest("SELECT", "execute")
        );

        assertFalse(result.isOk());
        assertEquals("PARSER", result.error().getStage());
        assertEquals("PARSER_MISSING_SEMICOLON", result.error().getCode());
        assertEquals(3, result.error().getLine());
        assertEquals(12, result.error().getColumn());
    }

    @Test
    void attachesStatementIndexToStorageFailure() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        assertTrue(fixture.execute(fixture.createTablePlan()).isOk());
        fixture.pageStorageClient.failNext(
                FakePageStorageClient.ALLOCATE_PAGE_FOR_TABLE,
                new DbError(
                        "req-storage", null, "STORAGE", "FILE_IO_ERROR",
                        "分配页失败", null, null, null
                )
        );

        var result = fixture.execute(
                "req-storage",
                "INSERT INTO student VALUES (1, 'Alice');",
                fixture.insertPlan(1, "Alice")
        );

        assertTrue(result.isOk());
        assertEquals("STORAGE", result.data().getError().getStage());
        assertEquals("FILE_IO_ERROR", result.data().getError().getCode());
        assertEquals("req-storage", result.data().getError().getRequestId());
        assertEquals(0, result.data().getError().getStatementIndex());
    }

    @Test
    void reportsAffectedRowsFromActualDeleteCount() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        assertTrue(fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(1, "Alice"),
                fixture.insertPlan(2, "Bob")
        ).isOk());

        var result = fixture.execute(
                fixture.deletePlan(fixture.binary(">", fixture.identifier("id"), fixture.literal("INT", 1)))
        );

        assertTrue(result.isOk());
        CommandResult deleted = (CommandResult) ((edu.csu.chainpage.engine.api.StatementExecutionResult)
                result.data().getResults().get(0)).getResult();
        assertEquals(1, deleted.affectedRows());
        assertEquals("1 row deleted", deleted.message());
    }
}
