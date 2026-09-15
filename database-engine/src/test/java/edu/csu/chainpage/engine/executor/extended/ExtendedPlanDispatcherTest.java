package edu.csu.chainpage.engine.executor.extended;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanDispatcher;
import edu.csu.chainpage.engine.plan.PlanNode;
import edu.csu.chainpage.engine.plan.PlanParser;
import edu.csu.chainpage.engine.storage.RowSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证四种扩展计划可以解析、登记并通过统一分派器执行
class ExtendedPlanDispatcherTest {

    @Test
    void parsesAllFourExtendedPlanKinds() {
        PlanParser parser = new PlanParser();
        List<Map<String, Object>> plans = List.of(
                updatePlan(),
                plan(
                        "Sort",
                        Map.of("keys", List.of(Map.of("column", "id", "direction", "ASC"))),
                        List.of(scanPlan("numbers", schema("id", "INT"))),
                        schema("id", "INT")
                ),
                plan(
                        "GroupBy",
                        Map.of(
                                "keys", List.of("category"),
                                "aggregates", List.of(Map.of(
                                        "function", "COUNT",
                                        "column", "*",
                                        "alias", "row_count"
                                ))
                        ),
                        List.of(scanPlan("numbers", List.of(
                                Map.of("name", "id", "dataType", "INT"),
                                Map.of("name", "category", "dataType", "VARCHAR")
                        ))),
                        List.of(
                                Map.of("name", "category", "dataType", "VARCHAR"),
                                Map.of("name", "row_count", "dataType", "INT")
                        )
                ),
                plan(
                        "Join",
                        Map.of("leftKey", "left_id", "rightKey", "right_id"),
                        List.of(
                                scanPlan("left_table", schema("left_id", "INT")),
                                scanPlan("right_table", schema("right_id", "INT"))
                        ),
                        List.of(
                                Map.of("name", "left_id", "dataType", "INT"),
                                Map.of("name", "right_id", "dataType", "INT")
                        )
                )
        );

        for (Map<String, Object> rawPlan : plans) {
            var result = parser.parse(rawPlan);
            assertTrue(result.isOk(), rawPlan.toString());
            assertEquals(rawPlan.get("kind"), result.data().kind());
        }
    }

    @Test
    void dispatchesUpdateSortGroupByAndJoinExecutors() {
        ExtendedExecutorTestSupport fixture = new ExtendedExecutorTestSupport();
        fixture.initializeStudentTable();
        PlanDispatcher dispatcher = new PlanDispatcher();
        Map<String, Integer> scanCalls = new HashMap<>();
        Map<String, RowSet> rowSets = testRowSets();
        dispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                String table = ((JsonPlanNode) plan).field("table").toString();
                scanCalls.merge(table, 1, Integer::sum);
                return DbResult.ok(ExecutionValue.rows(rowSets.get(table)));
            }

            @Override
            public boolean supports(String kind) {
                return "SeqScan".equals(kind);
            }
        });
        dispatcher.register(new UpdateExecutor(fixture.storageEngine, fixture.catalogManager));
        dispatcher.register(new SortExecutor(dispatcher));
        dispatcher.register(new GroupByExecutor(dispatcher));
        dispatcher.register(new JoinExecutor(dispatcher));

        var update = dispatcher.execute(
                "req-update",
                fixture.updatePlan(
                        List.of(fixture.assignment("name", fixture.literal("VARCHAR", "changed"))),
                        fixture.binary("=", fixture.identifier("id"), fixture.literal("INT", 1))
                )
        );
        var sort = dispatcher.execute("req-sort", sortNode());
        var group = dispatcher.execute("req-group", groupNode());
        var join = dispatcher.execute("req-join", joinNode());

        assertTrue(update.isOk());
        assertEquals(1, update.data().commandResult().affectedRows());
        assertTrue(sort.isOk());
        assertEquals(List.of(1, 2), sort.data().rowSet().rows().stream()
                .map(row -> row.values().valueOf("id"))
                .toList());
        assertTrue(group.isOk());
        assertEquals(2, group.data().rowSet().size());
        assertTrue(join.isOk());
        assertEquals(1, join.data().rowSet().size());
        assertEquals(1, scanCalls.get("left_table"));
        assertEquals(1, scanCalls.get("right_table"));
    }

    // 构造扩展分派测试使用的三个基础行集
    private Map<String, RowSet> testRowSets() {
        RowSet numbers = ExtendedExecutorTestSupport.rowSet(
                List.of(
                        new edu.csu.chainpage.engine.storage.ColumnSchema("id", "INT"),
                        new edu.csu.chainpage.engine.storage.ColumnSchema("category", "VARCHAR")
                ),
                List.of(
                        Map.of("id", 2, "category", "B"),
                        Map.of("id", 1, "category", "A")
                )
        );
        RowSet left = ExtendedExecutorTestSupport.rowSet(
                List.of(new edu.csu.chainpage.engine.storage.ColumnSchema("left_id", "INT")),
                List.of(Map.of("left_id", 1), Map.of("left_id", 2))
        );
        RowSet right = ExtendedExecutorTestSupport.rowSet(
                List.of(new edu.csu.chainpage.engine.storage.ColumnSchema("right_id", "INT")),
                List.of(Map.of("right_id", 2), Map.of("right_id", 3))
        );
        return Map.of("numbers", numbers, "left_table", left, "right_table", right);
    }

    // 构造Sort计划树
    private JsonPlanNode sortNode() {
        return new JsonPlanNode(
                "Sort",
                Map.of("kind", "Sort", "keys", List.of(Map.of("column", "id", "direction", "ASC"))),
                List.of(scanNode("numbers", List.of(
                        new ColumnSchema("id", "INT"),
                        new ColumnSchema("category", "VARCHAR")
                ))),
                List.of(new ColumnSchema("id", "INT"), new ColumnSchema("category", "VARCHAR"))
        );
    }

    // 构造GroupBy计划树
    private JsonPlanNode groupNode() {
        return new JsonPlanNode(
                "GroupBy",
                Map.of(
                        "kind", "GroupBy",
                        "keys", List.of("category"),
                        "aggregates", List.of(Map.of(
                                "function", "COUNT",
                                "column", "*",
                                "alias", "row_count"
                        ))
                ),
                List.of(scanNode("numbers", List.of(
                        new ColumnSchema("id", "INT"),
                        new ColumnSchema("category", "VARCHAR")
                ))),
                List.of(
                        new ColumnSchema("category", "VARCHAR"),
                        new ColumnSchema("row_count", "INT")
                )
        );
    }

    // 构造Join计划树
    private JsonPlanNode joinNode() {
        return new JsonPlanNode(
                "Join",
                Map.of("kind", "Join", "leftKey", "left_id", "rightKey", "right_id"),
                List.of(
                        scanNode("left_table", List.of(new ColumnSchema("left_id", "INT"))),
                        scanNode("right_table", List.of(new ColumnSchema("right_id", "INT")))
                ),
                List.of(new ColumnSchema("left_id", "INT"), new ColumnSchema("right_id", "INT"))
        );
    }

    // 构造JsonPlanNode形式的顺序扫描叶节点
    private JsonPlanNode scanNode(String table, List<ColumnSchema> schema) {
        return new JsonPlanNode(
                "SeqScan",
                Map.of("kind", "SeqScan", "table", table),
                List.of(),
                schema
        );
    }

    // 构造Map形式的Update计划
    private Map<String, Object> updatePlan() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("table", "student");
        fields.put("assignments", List.of(Map.of(
                "column", "name",
                "value", Map.of(
                        "kind", "LiteralExpr",
                        "literalType", "VARCHAR",
                        "value", "updated"
                )
        )));
        fields.put("predicate", null);
        return plan("Update", fields, List.of(), List.of());
    }

    // 构造Map形式的顺序扫描计划
    private Map<String, Object> scanPlan(String table, List<Map<String, String>> schema) {
        return plan("SeqScan", Map.of("table", table), List.of(), schema);
    }

    // 构造单列计划模式
    private List<Map<String, String>> schema(String name, String type) {
        return List.of(Map.of("name", name, "dataType", type));
    }

    // 构造包含kind、children和schema字段的原始计划对象
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
