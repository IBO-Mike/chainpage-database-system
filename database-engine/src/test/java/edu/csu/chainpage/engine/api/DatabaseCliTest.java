package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.common.JsonCodec;
import edu.csu.chainpage.engine.contract.CatalogSnapshot;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.contract.CompileResponse;
import edu.csu.chainpage.engine.lifecycle.DatabaseLifecycle;
import edu.csu.chainpage.engine.support.FakePageStorageClient;
import edu.csu.chainpage.engine.support.FakeSqlCompilerClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCliTest {

    @Test
    void readsJsonRequestAndWritesJsonResponse() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        compiler.enqueueSuccess(new CompileResponse(
                "req-1",
                List.of(new CompiledStatement(0, List.of(), null, null, "plan", null))
        ));
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                new FakePageStorageClient()
        );
        lifecycle.startup("startup");
        DatabaseApi api = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler,
                (requestId, statementIndex, plan) -> DbResult.ok(
                        new StatementExecutionResult(statementIndex, "SELECT", plan))
        );
        DatabaseCli cli = new DatabaseCli(api, new JsonCodec());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        cli.run(
                new ByteArrayInputStream(
                        "{\"sql\":\"SELECT 1;\",\"mode\":\"compile\"}\nquit\n"
                                .getBytes(StandardCharsets.UTF_8)
                ),
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        String response = output.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"ok\":true"));
        assertTrue(response.contains("\"results\""));
    }

    @Test
    void recognizesQuitAndExitCommands() {
        DatabaseCli cli = new DatabaseCli(null, new JsonCodec());

        assertTrue(cli.isQuitCommand("quit"));
        assertTrue(cli.isQuitCommand(" EXIT "));
    }

    @Test
    void recognizesExplainWithoutModeAndWritesExplainFields() {
        FakeSqlCompilerClient compiler = new FakeSqlCompilerClient();
        Map<String, Object> plan = scanPlan();
        compiler.enqueueSuccess(new CompileResponse(
                "req-1",
                List.of(new CompiledStatement(
                        0,
                        List.of(Map.of("type", "EXPLAIN")),
                        Map.of("kind", "ExplainStatement"),
                        Map.of("valid", true),
                        plan,
                        plan
                ))
        ));
        DatabaseLifecycle lifecycle = new DatabaseLifecycle(
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                new FakePageStorageClient()
        );
        lifecycle.startup("startup");
        DatabaseApi api = new DatabaseApi(
                lifecycle,
                requestId -> DbResult.ok(new CatalogSnapshot(List.of())),
                compiler,
                (requestId, statementIndex, rawPlan) -> DbResult.ok(
                        new StatementExecutionResult(statementIndex, "SELECT", rawPlan)
                )
        );
        DatabaseCli cli = new DatabaseCli(api, new JsonCodec());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        cli.run(
                new ByteArrayInputStream(
                        "{\"sql\":\"EXPLAIN SELECT * FROM student;\"}\nquit\n"
                                .getBytes(StandardCharsets.UTF_8)
                ),
                new PrintStream(output, true, StandardCharsets.UTF_8)
        );

        String response = output.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"tokens\""));
        assertTrue(response.contains("\"optimizedPlan\""));
        assertTrue(response.contains("\"tree\":\"SeqScan table=student\""));
        assertFalse(response.contains("\"rows\""));
    }

    // 构造用于CLI EXPLAIN测试的顺序扫描计划
    private Map<String, Object> scanPlan() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "SeqScan");
        plan.put("table", "student");
        plan.put("children", new ArrayList<>());
        plan.put("schema", List.of(Map.of("name", "id", "dataType", "INT")));
        return plan;
    }
}
