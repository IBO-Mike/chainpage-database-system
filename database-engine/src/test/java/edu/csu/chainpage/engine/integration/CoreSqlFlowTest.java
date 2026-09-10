package edu.csu.chainpage.engine.integration;

import edu.csu.chainpage.engine.api.DatabaseResponse;
import edu.csu.chainpage.engine.api.StatementExecutionResult;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.support.CoreEngineFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证数据库入口从计划编译结果到执行器和页式存储的核心SQL闭环
class CoreSqlFlowTest {

    @Test
    void createInsertSelectDeleteSelect() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        var result = fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(1, "Alice"),
                fixture.insertPlan(2, "Bob"),
                fixture.projectPlan(List.of("*"), fixture.scanPlan()),
                fixture.deletePlan(fixture.binary("=", fixture.identifier("id"), fixture.literal("INT", 1))),
                fixture.projectPlan(List.of("*"), fixture.scanPlan())
        );

        assertTrue(result.isOk());
        DatabaseResponse response = result.data();
        assertEquals(6, response.getResults().size());
        assertEquals("CREATE", statement(response, 0).getKind());
        assertEquals("INSERT", statement(response, 1).getKind());
        assertEquals("SELECT", statement(response, 3).getKind());
        assertEquals(List.of(List.of(1, "Alice"), List.of(2, "Bob")),
                command(response, 3).rows());
        assertEquals(1, command(response, 4).affectedRows());
        assertEquals(List.of(List.of(2, "Bob")), command(response, 5).rows());
    }

    @Test
    void selectWhereReturnsOnlyMatchedRows() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        var result = fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(1, "Alice"),
                fixture.insertPlan(2, "Bob"),
                fixture.projectPlan(
                        List.of("id", "name"),
                        fixture.filterPlan(fixture.binary(">", fixture.identifier("id"), fixture.literal("INT", 1)))
                )
        );

        assertTrue(result.isOk());
        DatabaseResponse response = result.data();
        assertEquals(List.of("id", "name"), command(response, 3).columns());
        assertEquals(List.of(List.of(2, "Bob")), command(response, 3).rows());
    }

    @Test
    void selectProjectsColumnsInRequestedOrder() {
        CoreEngineFixture fixture = new CoreEngineFixture();
        var result = fixture.execute(
                fixture.createTablePlan(),
                fixture.insertPlan(7, "Alice"),
                fixture.projectPlan(List.of("name", "id"), fixture.scanPlan())
        );

        assertTrue(result.isOk());
        CommandResult selected = command(result.data(), 2);
        assertEquals(List.of("name", "id"), selected.columns());
        assertEquals(List.of(List.of("Alice", 7)), selected.rows());
    }

    // 取得指定序号的语句执行结果
    private StatementExecutionResult statement(DatabaseResponse response, int index) {
        return (StatementExecutionResult) response.getResults().get(index);
    }

    // 取得指定序号的最终命令结果
    private CommandResult command(DatabaseResponse response, int index) {
        return (CommandResult) statement(response, index).getResult();
    }
}
