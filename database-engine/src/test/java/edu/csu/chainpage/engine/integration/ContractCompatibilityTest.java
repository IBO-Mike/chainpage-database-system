package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.api.DatabaseApi;
import edu.csu.chainpage.engine.api.DatabaseCli;
import edu.csu.chainpage.engine.api.DatabaseRequest;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证生产代码只依赖公开契约并使用规定JSON包络
class ContractCompatibilityTest {

    @Test
    void doesNotReferenceCompilerImplementationClasses() throws Exception {
        String source = allProductionSource();

        assertFalse(source.contains("edu.csu.chainpage.compiler"));
        assertFalse(source.contains("sql.compiler.internal"));
        assertTrue(source.contains("edu.csu.chainpage.engine.contract.SqlCompilerClient"));
    }

    @Test
    void doesNotReferenceStorageImplementationClasses() throws Exception {
        String source = allProductionSource();

        assertFalse(source.contains("org.chainpage.storage"));
        assertFalse(source.contains("page.storage.internal"));
        assertTrue(source.contains("edu.csu.chainpage.engine.contract.PageStorageClient"));
    }

    @Test
    void usesOnlyDocumentedJsonEnvelope() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        compiler.enqueueSuccess(new CompileResponse(
                "req-envelope",
                List.of(new CompiledStatement(0, List.of(), Map.of(), Map.of(), Map.of(), null))
        ));
        FakePageStorageClient pages = new FakePageStorageClient();
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                pages
        );
        lifecycle.startup("startup");
        DatabaseApi api = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler,
                (requestId, statementIndex, plan) -> DbResult.ok(
                        new StatementExecutionResult(statementIndex, "SELECT", plan)
                )
        );
        JsonCodec json = new JsonCodec();
        DatabaseCli cli = new DatabaseCli(api, json);
        DbResult<?> response = api.handle(
                "req-envelope",
                new DatabaseRequest("SELECT 1;", "compile")
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        cli.printResponse(new PrintStream(output, true, StandardCharsets.UTF_8), response);

        Map<String, Object> envelope = json.readObject(output.toString(StandardCharsets.UTF_8));
        assertEquals(java.util.Set.of("ok", "data", "error"), envelope.keySet());
        assertEquals(Boolean.TRUE, envelope.get("ok"));
        assertTrue(envelope.get("data") instanceof Map<?, ?>);
        assertEquals(null, envelope.get("error"));
    }

    // 合并读取数据库引擎核心生产代码；真实模块适配器属于边界实现
    private String allProductionSource() throws Exception {
        StringBuilder source = new StringBuilder();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".java")
                    && !value.toString().replace('\\', '/').contains("/engine/integration/")).toList()) {
                source.append(Files.readString(path));
            }
        }
        return source.toString();
    }
}
