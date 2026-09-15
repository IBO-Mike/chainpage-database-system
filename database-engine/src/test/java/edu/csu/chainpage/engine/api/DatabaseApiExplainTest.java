package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证数据库API能够处理EXPLAIN并保证没有执行和写页副作用
class DatabaseApiExplainTest {

    @Test
    void explainDoesNotCallStatementExecutorOrPageStorage() {
        FakePageStorageClient storage = new FakePageStorageClient();
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        compiler.enqueueSuccess(new CompileResponse(
                "req-explain",
                List.of(new CompiledStatement(
                        0,
                        List.of("EXPLAIN"),
                        Map.of("kind", "ExplainStatement"),
                        Map.of("valid", true),
                        scan(),
                        scan()
                ))
        ));
        AtomicInteger executionCalls = new AtomicInteger();
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                storage
        );
        lifecycle.startup("startup");
        storage.clearCalls();
        DatabaseApi api = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler,
                (requestId, statementIndex, plan) -> {
                    executionCalls.incrementAndGet();
                    return DbResult.ok(new StatementExecutionResult(statementIndex, "SELECT", plan));
                }
        );

        var result = api.handleExplain("req-explain", "EXPLAIN SELECT * FROM student;");

        assertTrue(result.isOk());
        assertEquals(0, executionCalls.get());
        assertEquals(0, storage.callCount(FakePageStorageClient.WRITE_PAGE));
        assertEquals(0, storage.callCount(FakePageStorageClient.ALLOCATE_PAGE_FOR_TABLE));
        assertEquals(0, storage.callCount(FakePageStorageClient.CREATE_TABLE_PAGES));
        assertEquals(0, storage.callCount(FakePageStorageClient.DROP_TABLE_PAGES));
    }

    @Test
    void explainRequiresReadyDatabase() {
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
                        new StatementExecutionResult(statementIndex, "SELECT", plan)
                )
        );

        var result = api.handleExplain("req-not-ready", "EXPLAIN SELECT * FROM student;");

        assertFalse(result.isOk());
        assertEquals("DATABASE_NOT_READY", result.error().getCode());
        assertTrue(compiler.receivedRequests().isEmpty());
    }

    // 构造用于EXPLAIN的顺序扫描计划
    private Map<String, Object> scan() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "SeqScan");
        plan.put("table", "student");
        plan.put("children", new ArrayList<>());
        plan.put("schema", List.of(Map.of("name", "id", "dataType", "INT")));
        return plan;
    }
}
