package edu.csu.chainpage.engine.explain;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证EXPLAIN服务返回全部编译产物且不会执行计划
class ExplainServiceTest {

    @Test
    void returnsTokensAstSemanticPlansAndTree() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        Object tokens = List.of(Map.of("type", "EXPLAIN"));
        Object ast = Map.of("kind", "ExplainStatement");
        Object semantic = Map.of("valid", true);
        Map<String, Object> original = scan("student");
        Map<String, Object> optimized = project(scan("student"));
        compiler.enqueueSuccess(new CompileResponse(
                "req-explain",
                List.of(new CompiledStatement(0, tokens, ast, semantic, original, optimized))
        ));
        ExplainService service = new ExplainService(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler
        );

        var result = service.explain("req-explain", "EXPLAIN SELECT id FROM student;");

        assertTrue(result.isOk());
        assertSame(tokens, result.data().tokens());
        assertSame(ast, result.data().ast());
        assertSame(semantic, result.data().semantic());
        assertEquals("SeqScan", result.data().plan().kind());
        assertEquals("Project", result.data().optimizedPlan().kind());
        assertTrue(result.data().tree().contains("SeqScan table=student"));
        assertEquals("EXPLAIN SELECT id FROM student;", compiler.receivedRequests().get(0).getSql());
        assertTrue(compiler.receivedRequests().get(0).isOptimize());
    }

    @Test
    void returnsCompilerErrorWithoutChangingIt() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        DbError compilerError = new DbError(
                "req-error",
                0,
                "PARSER",
                "PARSER_UNEXPECTED_TOKEN",
                "语法错误",
                1,
                9,
                null
        );
        compiler.enqueueFailure(compilerError);
        ExplainService service = new ExplainService(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler
        );

        var result = service.explain("req-error", "EXPLAIN SELECT FROM;");

        assertFalse(result.isOk());
        assertSame(compilerError, result.error());
    }

    @Test
    void rejectsNonExplainInputBeforeCallingCompiler() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        ExplainService service = new ExplainService(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler
        );

        var result = service.explain("req-invalid", "SELECT * FROM student;");

        assertFalse(result.isOk());
        assertEquals("INVALID_REQUEST", result.error().getCode());
        assertEquals("req-invalid", result.error().getRequestId());
        assertTrue(compiler.receivedRequests().isEmpty());
    }

    // 构造指定表的顺序扫描计划
    private Map<String, Object> scan(String table) {
        return plan("SeqScan", Map.of("table", table), List.of(), schema());
    }

    // 构造单列投影计划
    private Map<String, Object> project(Map<String, Object> child) {
        return plan("Project", Map.of("columns", List.of("id")), List.of(child), schema());
    }

    // 构造单列输出模式
    private List<Map<String, Object>> schema() {
        return List.of(Map.of("name", "id", "dataType", "INT"));
    }

    // 构造符合模块规约的原始计划对象
    private Map<String, Object> plan(
            String kind,
            Map<String, Object> fields,
            List<?> children,
            List<?> schema) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", kind);
        plan.putAll(fields);
        plan.put("children", new ArrayList<>(children));
        plan.put("schema", new ArrayList<>(schema));
        return plan;
    }
}
