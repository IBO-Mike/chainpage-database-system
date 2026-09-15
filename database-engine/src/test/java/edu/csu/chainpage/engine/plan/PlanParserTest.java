package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.contract.ColumnSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证六种基本计划节点的解析和结构校验
class PlanParserTest {

    private final PlanParser parser = new PlanParser();

    @Test
    void parsesAllSixCorePlanKinds() {
        List<Map<String, Object>> plans = List.of(
                plan("CreateTable", Map.of(
                        "table", "student",
                        "columns", List.of(Map.of("name", "id", "dataType", "INT"))
                ), List.of(), List.of()),
                plan("Insert", Map.of(
                        "table", "student",
                        "columns", List.of("id"),
                        "values", List.of(Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 1))
                ), List.of(), List.of()),
                plan("SeqScan", Map.of("table", "student"), List.of(), schema()),
                plan("Filter", Map.of(
                        "predicate", Map.of("kind", "LiteralExpr", "literalType", "BOOL", "value", true)
                ), List.of(seqScan()), schema()),
                plan("Project", Map.of("columns", List.of("id")), List.of(seqScan()), schema()),
                plan("Delete", nullableDeleteFields(), List.of(), List.of())
        );

        for (Map<String, Object> rawPlan : plans) {
            var result = parser.parse(rawPlan);
            assertTrue(result.isOk(), rawPlan.toString());
            assertEquals(rawPlan.get("kind"), result.data().kind());
        }
    }

    @Test
    void preservesFieldsAndMakesNestedCollectionsReadOnly() {
        Map<String, Object> raw = seqScan();
        var parsed = parser.parse(raw);

        assertTrue(parsed.isOk());
        JsonPlanNode node = (JsonPlanNode) parsed.data();
        assertEquals("student", node.field("table"));
        assertEquals(List.of(), node.children());
        assertEquals("id", node.schema().get(0).getName());

        @SuppressWarnings("unchecked")
        List<Object> rawChildren = (List<Object>) raw.get("children");
        rawChildren.add(Map.of("kind", "SeqScan"));
        assertTrue(node.children().isEmpty());
    }

    @Test
    void rejectsUnknownKindAndWrongChildCount() {
        var unknown = parser.parse(plan("FutureNode", Map.of(), List.of(), List.of()));
        var wrongChildren = parser.parse(plan("Filter", Map.of(
                "predicate", Map.of("kind", "LiteralExpr", "literalType", "BOOL", "value", true)
        ), List.of(), schema()));

        assertFalse(unknown.isOk());
        assertEquals("EXECUTOR_UNSUPPORTED_PLAN", unknown.error().getCode());
        assertFalse(wrongChildren.isOk());
        assertEquals("EXECUTOR_INVALID_PLAN", wrongChildren.error().getCode());
    }

    @Test
    void parseChildParsesNestedPlan() {
        var result = parser.parseChild(seqScan());

        assertTrue(result.isOk());
        assertEquals("SeqScan", result.data().kind());
    }

    private Map<String, Object> seqScan() {
        return plan("SeqScan", Map.of("table", "student"), List.of(), schema());
    }

    private Map<String, Object> nullableDeleteFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("table", "student");
        fields.put("predicate", null);
        return fields;
    }

    private List<Map<String, Object>> schema() {
        return List.of(Map.of("name", "id", "dataType", "INT"));
    }

    private Map<String, Object> plan(
            String kind,
            Map<String, Object> specificFields,
            List<?> children,
            List<?> schema) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", kind);
        fields.putAll(specificFields);
        fields.put("children", new ArrayList<>(children));
        fields.put("schema", new ArrayList<>(schema));
        return fields;
    }
}
