package edu.csu.chainpage.engine.contract;

import edu.csu.chainpage.engine.api.DatabaseApi;
import edu.csu.chainpage.engine.api.DatabaseRequest;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证数据库引擎与SQL编译器之间的公开契约
class SqlCompilerContractTest {

    @Test
    void sendsRequestIdSqlSnapshotAndOptimize() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        compiler.enqueueSuccess(response("req-contract"));
        CatalogSnapshot snapshot = new CatalogSnapshot(List.of(new TableSchema(
                "student",
                List.of(new ColumnSchema("id", "INT"))
        )));
        DatabaseApi api = api(compiler, new FakePageStorageClient(), snapshot);

        var result = api.handle(
                "req-contract",
                new DatabaseRequest("SELECT id FROM student;", "compile")
        );

        assertTrue(result.isOk());
        CompileRequest request = compiler.receivedRequests().get(0);
        assertEquals("req-contract", request.getRequestId());
        assertEquals("SELECT id FROM student;", request.getSql());
        assertSame(snapshot, request.getCatalogSnapshot());
        assertTrue(request.isOptimize());
    }

    @Test
    void acceptsAllRequiredCompileArtifacts() {
        Object tokens = List.of(Map.of("type", "SELECT", "line", 1, "column", 1));
        Object ast = Map.of("kind", "SelectStatement");
        Object semantic = Map.of("kind", "AnnotatedSelect", "table", "student");
        Object plan = Map.of("kind", "SeqScan", "children", List.of(), "schema", List.of());
        Object optimized = Map.of("kind", "SeqScan", "children", List.of(), "schema", List.of());
        CompiledStatement statement = new CompiledStatement(
                0, tokens, ast, semantic, plan, optimized
        );
        CompileResponse response = new CompileResponse("req-artifacts", List.of(statement));

        assertEquals("req-artifacts", response.getRequestId());
        assertEquals(0, response.getStatements().get(0).getStatementIndex());
        assertSame(tokens, statement.getTokens());
        assertSame(ast, statement.getAst());
        assertSame(semantic, statement.getSemantic());
        assertSame(plan, statement.getPlan());
        assertSame(optimized, statement.getOptimizedPlan());
    }

    @Test
    void preservesCompilerErrorWithoutStorageCall() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        FakePageStorageClient storage = new FakePageStorageClient();
        DbError compilerError = new DbError(
                "req-error", 0, "SEMANTIC", "SEMANTIC_COLUMN_NOT_FOUND",
                "列不存在", 1, 8, null
        );
        compiler.enqueueFailure(compilerError);
        DatabaseApi api = api(compiler, storage, new CatalogSnapshot(List.of()));
        storage.clearCalls();

        var result = api.handle(
                "req-error",
                new DatabaseRequest("SELECT missing FROM student;", "execute")
        );

        assertFalse(result.isOk());
        assertSame(compilerError, result.error());
        assertEquals(0, storage.callCount(FakePageStorageClient.GET_PAGE));
        assertEquals(0, storage.callCount(FakePageStorageClient.WRITE_PAGE));
        assertEquals(0, storage.callCount(FakePageStorageClient.ALLOCATE_PAGE_FOR_TABLE));
    }

    // 创建已经启动且只依赖公开契约的数据库API
    private DatabaseApi api(
            FakeSqlCompilerClient compiler,
            FakePageStorageClient storage,
            CatalogSnapshot snapshot) {
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(snapshot),
                storage
        );
        lifecycle.startup("startup");
        return new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(snapshot),
                compiler,
                (requestId, statementIndex, plan) -> DbResult.ok(
                        new StatementExecutionResult(statementIndex, "SELECT", plan)
                )
        );
    }

    // 构造包含全部编译字段的最小成功响应
    private CompileResponse response(String requestId) {
        return new CompileResponse(requestId, List.of(new CompiledStatement(
                0,
                List.of(Map.of("type", "SELECT")),
                Map.of("kind", "SelectStatement"),
                Map.of("kind", "AnnotatedSelect"),
                Map.of("kind", "SeqScan"),
                Map.of("kind", "SeqScan")
        )));
    }
}
