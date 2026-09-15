package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.explain.ExplainResult;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证命令行默认文本输出的主要显示场景
class HumanReadableFormatterTest {

    private final HumanReadableFormatter formatter = new HumanReadableFormatter();

    @Test
    void formatsSelectAsMysqlStyleTable() {
        String output = formatter.formatTable(
                List.of("id", "name"),
                Arrays.asList(
                        Arrays.asList(1, "Alice"),
                        Arrays.asList(2, null)
                )
        );

        assertTrue(output.contains("+----+-------+"));
        assertTrue(output.contains("| id | name  |"));
        assertTrue(output.contains("| 1  | Alice |"));
        assertTrue(output.contains("| 2  | NULL  |"));
        assertTrue(output.contains("2 rows in set"));
        assertFalse(output.contains("\"ok\""));
    }

    @Test
    void formatsMutationAsQueryOk() {
        String output = formatter.format(DbResult.ok(DatabaseResponse.executeSuccess(List.of(
                new StatementExecutionResult(0, "INSERT", CommandResult.insert())
        ))));

        assertEquals("Query OK, 1 row affected", output);
    }

    @Test
    void formatsPartialFailureAfterCompletedStatements() {
        DbError error = new DbError(
                "req-1", 1, "COMPILER", "SYNTAX_ERROR", "语句解析失败", 3, 5, null
        );
        String output = formatter.format(DbResult.ok(DatabaseResponse.failure(
                List.of(new StatementExecutionResult(0, "INSERT", CommandResult.insert())),
                error
        )));

        assertTrue(output.startsWith("Query OK, 1 row affected"));
        assertTrue(output.contains("ERROR SYNTAX_ERROR (COMPILER): 语句解析失败"));
        assertTrue(output.contains("statement 2, line 3, column 5"));
    }

    @Test
    void formatsCompileResultAsPlanSummary() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", "SeqScan");
        plan.put("table", "student");
        plan.put("columns", List.of("id", "name"));
        String output = formatter.format(DbResult.ok(DatabaseResponse.compileSuccess(List.of(
                new CompiledStatement(0, List.of(), null, null, plan, plan)
        ))));

        assertTrue(output.contains("Statement 1: compilation OK"));
        assertTrue(output.contains("Plan: SeqScan"));
        assertTrue(output.contains("table=student"));
        assertTrue(output.contains("Optimized plan: SeqScan"));
    }

    @Test
    void formatsFailureWithErrorDetails() {
        DbError error = new DbError(
                "req-1", null, "API", "INVALID_REQUEST", "请求格式错误", null, null, null
        );

        assertEquals(
                "ERROR INVALID_REQUEST (API): 请求格式错误",
                formatter.format(DbResult.fail(error))
        );
    }

    @Test
    void formatsExplainSelectAsIndentedTreeWithoutRawExpressionMap() {
        JsonPlanNode scan = new JsonPlanNode("SeqScan", Map.of("table", "student"), List.of(), List.of());
        Map<String, Object> predicate = Map.of(
                "kind", "BinaryExpr", "operator", "=",
                "left", Map.of("kind", "IdentifierExpr", "name", "id", "loc", Map.of("line", 1)),
                "right", Map.of("kind", "LiteralExpr", "value", 62, "literalType", "INT")
        );
        JsonPlanNode filter = new JsonPlanNode("Filter", Map.of("predicate", predicate),
                List.of(scan), List.of());
        JsonPlanNode project = new JsonPlanNode("Project", Map.of("columns", List.of("id", "name", "age")),
                List.of(filter), List.of());
        ExplainResult explain = new ExplainResult(null, null, null, project, project, "old raw tree");

        assertEquals("""
                EXPLAIN
                └── Project
                    ├── 输出列: id, name, age
                    └── Filter
                        ├── 条件: id = 62
                        └── SeqScan
                            └── 数据表: student""",
                formatter.format(DbResult.ok(explain)));
    }

    @Test
    void formatsExplainInsertWithoutExecutingIt() {
        JsonPlanNode insert = new JsonPlanNode("Insert", Map.of(
                "table", "student",
                "columns", List.of("id", "name", "age"),
                "values", List.of(
                        Map.of("kind", "LiteralExpr", "value", 2),
                        Map.of("kind", "LiteralExpr", "value", "jys"),
                        Map.of("kind", "LiteralExpr", "value", 19)
                )
        ), List.of(), List.of());

        assertEquals("""
                EXPLAIN
                └── Insert
                    ├── 目标表: student
                    ├── 写入列: id, name, age
                    └── 写入值: 2, 'jys', 19""",
                formatter.format(DbResult.ok(new ExplainResult(null, null, null,
                        insert, insert, "old raw tree"))));
    }
}
