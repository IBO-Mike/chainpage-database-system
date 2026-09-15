package edu.csu.chainpage.engine.plan;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.ColumnSchema;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.executor.ExecutionValue;
import edu.csu.chainpage.engine.executor.PlanExecutor;
import edu.csu.chainpage.engine.storage.InternalRow;
import edu.csu.chainpage.engine.storage.Row;
import edu.csu.chainpage.engine.storage.RowId;
import edu.csu.chainpage.engine.storage.RowSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证计划树先执行子节点，且父执行器可以取得已经完成的子结果
class PlanTreeExecutionTest {

    @Test
    void executesChildBeforeParentWithoutExecutingChildTwice() {
        PlanDispatcher dispatcher = new PlanDispatcher();
        List<String> order = new ArrayList<>();
        int[] childCalls = {0};

        dispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                order.add("child");
                childCalls[0]++;
                RowSet rows = new RowSet(
                        List.of(new edu.csu.chainpage.engine.storage.ColumnSchema("id", "INT")),
                        List.of(new InternalRow(new RowId(0, 0), new Row(Map.of("id", 1))))
                );
                return DbResult.ok(ExecutionValue.rows(rows));
            }

            @Override
            public boolean supports(String kind) {
                return "SeqScan".equals(kind);
            }
        });
        dispatcher.register(new PlanExecutor() {
            @Override
            public DbResult<ExecutionValue> execute(String requestId, PlanNode plan) {
                order.add("parent");
                var child = dispatcher.executeChild(requestId, plan, 0);
                assertTrue(child.isOk());
                assertTrue(child.data().isRowSet());
                assertEquals(1, child.data().rowSet().size());
                return DbResult.ok(ExecutionValue.command(CommandResult.select(List.of("id"), List.of(List.of(1)))));
            }

            @Override
            public boolean supports(String kind) {
                return "Project".equals(kind);
            }
        });

        PlanNode child = new JsonPlanNode(
                "SeqScan",
                Map.of("kind", "SeqScan", "table", "student"),
                List.of(),
                List.of(new ColumnSchema("id", "INT"))
        );
        PlanNode parent = new JsonPlanNode(
                "Project",
                Map.of("kind", "Project", "columns", List.of("id")),
                List.of(child),
                List.of(new ColumnSchema("id", "INT"))
        );

        var result = dispatcher.execute("req-1", parent);

        assertTrue(result.isOk());
        assertEquals(List.of("child", "parent"), order);
        assertEquals(1, childCalls[0]);
    }
}
