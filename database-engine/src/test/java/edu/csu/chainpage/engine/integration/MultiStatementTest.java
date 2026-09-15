package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.support.CoreEngineFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证多语句执行的顺序、前序结果保留和编译失败短路
class MultiStatementTest {

    @Test
    void preservesCompletedResultsBeforeFailure() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        var result = fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(1, "Alice"),
                fixture.createTablePlan()
        );

        assertTrue(result.isOk());
        assertEquals(2, result.data().getResults().size());
        assertEquals("TABLE_ALREADY_EXISTS", result.data().getError().getCode());
        assertEquals(2, result.data().getError().getStatementIndex());
    }

    @Test
    void doesNotExecuteWhenCompilerFails() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        fixture.compilerClient.enqueueFailure(new DbError(
                "req-compile-failed",
                null,
                "PARSER",
                "PARSER_INVALID_SQL",
                "SQL语句不完整",
                1,
                8,
                null
        ));

        var result = fixture.databaseApi.handle(
                "req-compile-failed",
                new edu.csu.chainpage.engine.api.DatabaseRequest("BROKEN", "execute")
        );

        assertFalse(result.isOk());
        assertEquals("PARSER", result.error().getStage());
        assertEquals(0, fixture.pageStorageClient.callCount(
                edu.csu.chainpage.engine.support.FakePageStorageClient.ALLOCATE_PAGE_FOR_TABLE
        ));
        assertEquals(1, fixture.compilerClient.receivedRequests().size());
    }
}
