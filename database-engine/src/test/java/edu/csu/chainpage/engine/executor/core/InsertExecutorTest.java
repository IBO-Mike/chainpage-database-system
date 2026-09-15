package edu.csu.chainpage.engine.executor.core;

import edu.csu.chainpage.engine.executor.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证插入按目录列顺序组装记录、字面量检查和缺表错误
class InsertExecutorTest {

    @Test
    void insertsCompleteRowInCatalogOrder() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        new CreateTableExecutor(fixture.storageEngine, fixture.catalogManager)
                .execute("create", fixture.createTablePlan());
        InsertExecutor executor = new InsertExecutor(fixture.storageEngine, fixture.catalogManager);
        var plan = new edu.csu.chainpage.engine.plan.JsonPlanNode(
                "Insert",
                Map.of(
                        "kind", "Insert", "table", "student",
                        "columns", List.of("name", "id"),
                        "values", List.of(
                                Map.of("kind", "LiteralExpr", "literalType", "VARCHAR", "value", "Alice"),
                                Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1)
                        )
                ), List.of(), List.of()
        );

        var result = executor.execute("insert", plan);
        var scanned = fixture.storageEngine.scanRows("scan", 
                edu.csu.chainpage.engine.executor.core.ExecutorSupport.toStorageSchema(fixture.contractSchema()));

        assertTrue(result.isOk());
        assertEquals("INSERT", result.data().commandResult().kind());
        assertEquals(1, scanned.data().size());
        assertEquals(1, scanned.data().rows().get(0).values().valueOf("id"));
        assertEquals("Alice", scanned.data().rows().get(0).values().valueOf("name"));
    }

    @Test
    void rejectsMissingTableUnknownColumnAndNonLiteralValue() {
        CoreExecutorTestSupport fixture = new CoreExecutorTestSupport();
        fixture.initialize();
        InsertExecutor executor = new InsertExecutor(fixture.storageEngine, fixture.catalogManager);

        var missing = executor.execute("missing", fixture.insertPlan(1, "Alice"));
        var unknown = executor.buildRow(
                fixture.contractSchema(),
                new edu.csu.chainpage.engine.plan.JsonPlanNode(
                        "Insert",
                        Map.of("kind", "Insert", "table", "student", "columns", List.of("id", "other"),
                                "values", List.of(
                                        Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1),
                                        Map.of("kind", "LiteralExpr", "literalType", "VARCHAR", "value", "x")
                                )), List.of(), List.of()
                )
        );
        var expression = executor.literalValue(Map.of("kind", "IdentifierExpr", "name", "id"));

        assertFalse(missing.isOk());
        assertEquals("EXECUTOR_TABLE_NOT_FOUND", missing.error().getCode());
        assertFalse(unknown.isOk());
        assertFalse(expression.isOk());
        assertEquals("EXECUTOR_LITERAL_ERROR", expression.error().getCode());
    }
}
