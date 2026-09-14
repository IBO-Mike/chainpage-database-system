package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.api.DatabaseRequest;
import edu.csu.chainpage.engine.api.DatabaseResponse;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.plan.PlanParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 在组内真实模块适配器可用时执行三模块端到端联调
class RealModuleIntegrationTest {

    private static final String HARNESS_PROPERTY = "chainpage.realModuleHarness";
    @TempDir
    Path dataDirectory;

    @Test
    void executesCoreFlowAgainstRealCompilerAndStorage() throws Exception {
        try (RealModuleTestHarness harness = createHarness()) {
            harness.executeCoreFlowAgainstRealCompilerAndStorage();
        }
    }

    @Test
    void executesMultiStatementAndErrorCasesAgainstRealModules() throws Exception {
        try (RealModuleTestHarness harness = createHarness()) {
            harness.executeMultiStatementAndErrorCasesAgainstRealModules();
        }
    }

    @Test
    void restartsAgainstRealStorage() throws Exception {
        try (RealModuleTestHarness harness = createHarness()) {
            harness.restartAgainstRealStorage();
        }
    }

    @Test
    void executesExtendedSqlAgainstRealModules() {
        try (ChainPageDatabase database = new ChainPageDatabase(dataDirectory)) {
            execute(database, "CREATE TABLE metrics(id INT, value INT);");
            execute(database, "INSERT INTO metrics(id,value) VALUES (1,10);");
            execute(database, "INSERT INTO metrics(id,value) VALUES (2,20);");
            assertEquals(1, execute(database, "UPDATE metrics SET value=30 WHERE id=1;").affectedRows());
            assertTrue(database.api().handle("explain-guard",
                    new DatabaseRequest("EXPLAIN UPDATE metrics SET value=99 WHERE id=1;", "execute")).error()
                    .getCode().equals("INVALID_REQUEST"));
            assertEquals(List.of(List.of(30)),
                    execute(database, "SELECT value FROM metrics WHERE id=1;").rows());
            assertEquals(List.of(List.of(2, 20), List.of(1, 30)),
                    execute(database, "SELECT id,value FROM metrics ORDER BY id DESC;").rows());
            assertEquals(List.of(List.of(2)),
                    execute(database, "SELECT id FROM metrics WHERE id>1 ORDER BY id;").rows());
            CommandResult grouped = execute(database,
                    "SELECT value,COUNT(*) AS total FROM metrics GROUP BY value ORDER BY value;");
            assertEquals(List.of("value", "total"), grouped.columns());
            assertEquals(List.of(List.of(20, 1), List.of(30, 1)), grouped.rows());
            assertEquals(List.of(List.of(1, 30), List.of(2, 20)),
                    execute(database, "SELECT a.id,b.value FROM metrics a JOIN metrics b ON a.id=b.id ORDER BY a.id;").rows());
        }
    }

    @Test
    void storesManyRowsAcrossPages() {
        try (ChainPageDatabase database = new ChainPageDatabase(dataDirectory)) {
            execute(database, "CREATE TABLE many(id INT, label VARCHAR);");
            String label = "x".repeat(180);
            for (int index = 0; index < 100; index++) {
                execute(database, "INSERT INTO many(id,label) VALUES (" + index + ",'" + label + "');");
            }
            assertEquals(100, execute(database, "SELECT id FROM many;").rows().size());
        }
        try (ChainPageDatabase restarted = new ChainPageDatabase(dataDirectory)) {
            assertEquals(100, execute(restarted, "SELECT id FROM many;").rows().size());
        }
    }

    @Test
    void rollsBackAndCommitsAgainstRealPages() {
        try (ChainPageDatabase database = new ChainPageDatabase(dataDirectory)) {
            execute(database, "CREATE TABLE accounts(id INT);");
            database.authorization().grant("alice", "INSERT", "accounts");
            long rolledBack = database.transactions().begin("first").data().txId();
            CompiledStatement first = compileOne(database, "INSERT INTO accounts(id) VALUES (1);");
            assertTrue(database.transactions().execute("tx-insert", rolledBack,
                    new PlanParser().parse(first.getOptimizedPlan()).data(), "alice").isOk());
            assertTrue(database.transactions().rollback("tx-rollback", rolledBack).isOk());
            assertEquals(List.of(), execute(database, "SELECT id FROM accounts;").rows());

            long committed = database.transactions().begin("second").data().txId();
            CompiledStatement second = compileOne(database, "INSERT INTO accounts(id) VALUES (2);");
            assertTrue(database.transactions().execute("tx-insert", committed,
                    new PlanParser().parse(second.getOptimizedPlan()).data(), "alice").isOk());
            assertTrue(database.transactions().commit("tx-commit", committed).isOk());
            assertEquals(List.of(List.of(2)), execute(database, "SELECT id FROM accounts;").rows());
        }
        try (ChainPageDatabase restarted = new ChainPageDatabase(dataDirectory)) {
            assertEquals(List.of(List.of(2)), execute(restarted, "SELECT id FROM accounts;").rows());
        }
    }

    private CompiledStatement compileOne(ChainPageDatabase database, String sql) {
        DbResult<DatabaseResponse> result = database.api().handle("tx-compile",
                new DatabaseRequest(sql, "compile"));
        assertTrue(result.isOk(), () -> sql + ": " + result.error().getMessage());
        return (CompiledStatement) result.data().getResults().get(0);
    }

    private CommandResult execute(ChainPageDatabase database, String sql) {
        DbResult<DatabaseResponse> result = database.api().handle("real-module-test",
                new DatabaseRequest(sql, "execute"));
        assertTrue(result.isOk(), () -> sql + ": " + result.error().getMessage());
        assertNull(result.data().getError(), sql);
        assertEquals(1, result.data().getResults().size());
        return (CommandResult) ((StatementExecutionResult) result.data().getResults().get(0)).getResult();
    }

    // 从Maven系统属性加载组内提供的真实模块测试适配器
    private RealModuleTestHarness createHarness() throws Exception {
        String className = System.getProperty(HARNESS_PROPERTY);
        if (className == null || className.isBlank()) {
            return new InProcessRealModuleHarness(dataDirectory);
        }
        Class<?> type = Class.forName(className);
        Object instance = type.getDeclaredConstructor().newInstance();
        Assumptions.assumeTrue(
                instance instanceof RealModuleTestHarness,
                "真实模块适配器必须实现RealModuleTestHarness"
        );
        return (RealModuleTestHarness) instance;
    }
}
