package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.api.DatabaseRequest;
import edu.csu.chainpage.engine.api.DatabaseResponse;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.CommandResult;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 默认使用三个真实模块的联调实现，不依赖外部测试适配器。 */
final class InProcessRealModuleHarness implements RealModuleTestHarness {
    private final Path dataDirectory;

    InProcessRealModuleHarness(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    @Override
    public void executeCoreFlowAgainstRealCompilerAndStorage() {
        try (ChainPageDatabase database = new ChainPageDatabase(dataDirectory)) {
            assertEquals("CREATE", command(database, "CREATE TABLE student(id INT, name VARCHAR, age INT);").kind());
            assertEquals(1, command(database, "INSERT INTO student(id,name,age) VALUES (1,'Alice',20);").affectedRows());
            assertEquals(1, command(database, "INSERT INTO student(id,name,age) VALUES (2,'Bob',17);").affectedRows());
            CommandResult selected = command(database, "SELECT id,name FROM student WHERE age > 18;");
            assertEquals(List.of("id", "name"), selected.columns());
            assertEquals(List.of(List.of(1, "Alice")), selected.rows());
            assertEquals(1, command(database, "DELETE FROM student WHERE id = 1;").affectedRows());
            assertEquals(List.of(List.of(2, "Bob", 17)), command(database, "SELECT * FROM student;").rows());
        }
    }

    @Override
    public void executeMultiStatementAndErrorCasesAgainstRealModules() {
        try (ChainPageDatabase database = new ChainPageDatabase(dataDirectory)) {
            DatabaseResponse sameRequest = response(database,
                    "CREATE TABLE created_in_batch(id INT);"
                            + " INSERT INTO created_in_batch(id) VALUES (7);"
                            + " SELECT id FROM created_in_batch;");
            assertNull(sameRequest.getError());
            assertEquals(List.of(List.of(7)), ((CommandResult) ((StatementExecutionResult)
                    sameRequest.getResults().get(2)).getResult()).rows());
            command(database, "CREATE TABLE items(id INT, label VARCHAR);");
            DatabaseResponse multiple = response(database,
                    "INSERT INTO items(id,label) VALUES (1,'first');"
                            + " INSERT INTO items(id,label) VALUES (2,'second');"
                            + " SELECT id FROM items WHERE id > 1;");
            assertNull(multiple.getError());
            assertEquals(3, multiple.getResults().size());
            assertEquals(List.of(List.of(2)), ((CommandResult) ((StatementExecutionResult)
                    multiple.getResults().get(2)).getResult()).rows());

            DbResult<DatabaseResponse> invalid = database.api().handle("invalid-sql",
                    new DatabaseRequest("SELECT * FROM missing_table;", "execute"));
            assertFalse(invalid.isOk());
            assertNotNull(invalid.error().getCode());
            assertNotNull(invalid.error().getStage());
            assertNotNull(invalid.error().getLine());
            assertNotNull(invalid.error().getColumn());
        }
    }

    @Override
    public void restartAgainstRealStorage() {
        try (ChainPageDatabase database = new ChainPageDatabase(dataDirectory)) {
            command(database, "CREATE TABLE persisted(id INT, label VARCHAR);");
            command(database, "INSERT INTO persisted(id,label) VALUES (9,'kept');");
        }
        try (ChainPageDatabase restarted = new ChainPageDatabase(dataDirectory)) {
            assertEquals(List.of(List.of(9, "kept")), command(restarted, "SELECT * FROM persisted;").rows());
        }
    }

    private static CommandResult command(ChainPageDatabase database, String sql) {
        DatabaseResponse result = response(database, sql);
        assertNull(result.getError(), "执行错误: " + sql);
        assertEquals(1, result.getResults().size());
        Object value = ((StatementExecutionResult) result.getResults().get(0)).getResult();
        assertTrue(value instanceof CommandResult);
        return (CommandResult) value;
    }

    private static DatabaseResponse response(ChainPageDatabase database, String sql) {
        DbResult<DatabaseResponse> result = database.api().handle("integration-test",
                new DatabaseRequest(sql, "execute"));
        assertTrue(result.isOk(), () -> "请求失败: " + sql + " / " + result.error().getMessage());
        return result.data();
    }
}
