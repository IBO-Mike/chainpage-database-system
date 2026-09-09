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
import java.util.List;

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
}
