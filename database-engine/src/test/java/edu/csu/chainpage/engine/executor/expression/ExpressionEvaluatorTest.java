package edu.csu.chainpage.engine.executor.expression;

import edu.csu.chainpage.engine.storage.Row;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证基本表达式的递归求值、类型错误和谓词结果检查
class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();
    private final Row row = new Row(Map.of("id", 3, "name", "Alice"));

    @Test
    void evaluatesIdentifierLiteralComparisonAndNot() {
        var expression = Map.of(
                "kind", "UnaryExpr",
                "operator", "NOT",
                "operand", Map.of(
                        "kind", "BinaryExpr",
                        "operator", "=",
                        "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                        "right", Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 4)
                )
        );

        var result = evaluator.evaluatePredicate(expression, row);

        assertTrue(result.isOk());
        assertTrue(result.data());
    }

    @Test
    void evaluatesArithmeticAndBooleanOperators() {
        var expression = Map.of(
                "kind", "BinaryExpr",
                "operator", "AND",
                "left", Map.of(
                        "kind", "BinaryExpr",
                        "operator", ">",
                        "left", Map.of("kind", "BinaryExpr", "operator", "+",
                                "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                                "right", Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1)),
                        "right", Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 3)
                ),
                "right", Map.of("kind", "BinaryExpr", "operator", "=",
                        "left", Map.of("kind", "IdentifierExpr", "name", "name"),
                        "right", Map.of("kind", "LiteralExpr", "literalType", "VARCHAR", "value", "Alice"))
        );

        var result = evaluator.evaluatePredicate(expression, row);

        assertTrue(result.isOk());
        assertTrue(result.data());
        assertEquals(4, evaluator.evaluate(
                Map.of("kind", "BinaryExpr", "operator", "+",
                        "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                        "right", Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1)), row
        ).data());
    }

    @Test
    void rejectsMissingColumnNonBooleanPredicateAndInvalidOperator() {
        var missingColumn = evaluator.evaluatePredicate(
                Map.of("kind", "IdentifierExpr", "name", "missing"), row
        );
        var nonBoolean = evaluator.evaluatePredicate(
                Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1), row
        );
        var invalidOperator = evaluator.evaluate(
                Map.of("kind", "BinaryExpr", "operator", "~",
                        "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                        "right", Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1)), row
        );

        assertFalse(missingColumn.isOk());
        assertEquals("EXECUTOR_PREDICATE_ERROR", missingColumn.error().getCode());
        assertFalse(nonBoolean.isOk());
        assertEquals("EXECUTOR_PREDICATE_ERROR", nonBoolean.error().getCode());
        assertFalse(invalidOperator.isOk());
        assertEquals("EXECUTOR_PREDICATE_ERROR", invalidOperator.error().getCode());
    }
}
