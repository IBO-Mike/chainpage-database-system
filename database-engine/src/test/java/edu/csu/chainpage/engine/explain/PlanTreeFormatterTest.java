package edu.csu.chainpage.engine.explain;

import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.plan.PlanParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证计划树格式化器能够完整展示节点层级和关键字段
class PlanTreeFormatterTest {

    @Test
    void formatsEveryLevelOfNestedPlanTree() {
        PlanNode plan = new PlanParser().parse(project(filter(scan()))).data();

        String tree = new PlanTreeFormatter().format(plan);

        String[] lines = tree.split("\\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].startsWith("Project columns=[id]"));
        assertTrue(lines[1].startsWith("  Filter predicate="));
        assertEquals("    SeqScan table=student", lines[2]);
    }

    @Test
    void describesImportantFieldsOfExtendedPlan() {
        PlanNode plan = new PlanParser().parse(plan(
                "Join",
                Map.of("leftKey", "student.id", "rightKey", "score.student_id"),
                List.of(scan(), scan("score")),
                schema()
        )).data();

        String description = new PlanTreeFormatter().describe(plan);

        assertTrue(description.contains("Join"));
        assertTrue(description.contains("leftKey=student.id"));
        assertTrue(description.contains("rightKey=score.student_id"));
    }

    // 构造投影计划
    private Map<String, Object> project(Map<String, Object> child) {
        return plan("Project", Map.of("columns", List.of("id")), List.of(child), schema());
    }

    // 构造筛选计划
    private Map<String, Object> filter(Map<String, Object> child) {
        return plan(
                "Filter",
                Map.of("predicate", Map.of(
                        "kind", "BinaryExpr",
                        "operator", ">",
                        "left", Map.of("kind", "IdentifierExpr", "name", "id"),
                        "right", Map.of("kind", "LiteralExpr", "literalType", "INT", "value", 0)
                )),
                List.of(child),
                schema()
        );
    }

    // 构造student表的顺序扫描计划
    private Map<String, Object> scan() {
        return scan("student");
    }

    // 构造指定表的顺序扫描计划
    private Map<String, Object> scan(String table) {
        return plan("SeqScan", Map.of("table", table), List.of(), schema());
    }

    // 构造单列输出模式
    private List<Map<String, Object>> schema() {
        return List.of(Map.of("name", "id", "dataType", "INT"));
    }

    // 构造符合模块规约的原始计划对象
    private Map<String, Object> plan(
            String kind,
            Map<String, Object> fields,
            List<?> children,
            List<?> schema) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", kind);
        plan.putAll(fields);
        plan.put("children", new ArrayList<>(children));
        plan.put("schema", new ArrayList<>(schema));
        return plan;
    }
}
