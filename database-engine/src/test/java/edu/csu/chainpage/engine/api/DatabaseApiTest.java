package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseApiTest {

    @Test
    void compileModeReturnsAllCompiledStatements() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        compiler.enqueueSuccess(new CompileResponse(
                "req-1",
                List.of(new CompiledStatement(0, List.of(), null, null, "plan", null))
        ));
        DatabaseApi api = createApi(compiler, (requestId, statementIndex, plan) ->
                DbResult.ok(new StatementExecutionResult(statementIndex, "SELECT", plan)));

        var result = api.handle("req-1", new DatabaseRequest("SELECT 1;", "compile"));

        assertTrue(result.isOk());
        assertEquals(1, result.data().getResults().size());
        assertEquals(0, compiler.receivedRequests().get(0).getCatalogSnapshot().getTables().size());
    }

    @Test
    void executeModeUsesOptimizedPlanWhenAvailable() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        compiler.enqueueSuccess(new CompileResponse(
                "req-1",
                List.of(new CompiledStatement(0, List.of(), null, null, "original", "optimized"))
        ));
        AtomicReference<Object> receivedPlan = new AtomicReference<>();
        DatabaseApi api = createApi(compiler, (requestId, statementIndex, plan) -> {
            receivedPlan.set(plan);
            return DbResult.ok(new StatementExecutionResult(statementIndex, "SELECT", plan));
        });

        var result = api.handle("req-1", new DatabaseRequest("SELECT 1;", "execute"));

        assertTrue(result.isOk());
        assertEquals("optimized", receivedPlan.get());
    }

    @Test
    void executionFailureContainsStatementIndex() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        compiler.enqueueSuccess(new CompileResponse(
                "req-1",
                List.of(
                        new CompiledStatement(0, List.of(), null, null, "first", null),
                        new CompiledStatement(1, List.of(), null, null, "second", null)
                )
        ));
        DatabaseApi api = createApi(compiler, (requestId, statementIndex, plan) ->
                statementIndex == 1
                        ? DbResult.fail(new edu.csu.chainpage.engine.common.DbError(
                                requestId, null, "EXECUTOR", "FAILED", "执行失败", null, null, null))
                        : DbResult.ok(new StatementExecutionResult(statementIndex, "SELECT", plan)));

        var result = api.handle("req-1", new DatabaseRequest("SELECT 1; SELECT 2;", "execute"));

        assertFalse(result.isOk());
        assertEquals(1, result.error().getStatementIndex());
    }

    @Test
    void rejectsRequestBeforeCallingCompilerWhenDatabaseIsNotReady() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                new FakePageStorageClient()
        );
        DatabaseApi api = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler,
                (requestId, statementIndex, plan) -> DbResult.ok(
                        new StatementExecutionResult(statementIndex, "SELECT", plan))
        );

        var result = api.handle("req-1", new DatabaseRequest("SELECT 1;", "execute"));

        assertFalse(result.isOk());
        assertEquals(0, compiler.receivedRequests().size());
    }

    private DatabaseApi createApi(
            FakeSqlCompilerClient compiler,
            StatementExecutor executor) {
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                new FakePageStorageClient()
        );
        lifecycle.startup("startup");
        return new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler,
                executor
        );
    }
}
