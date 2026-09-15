package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.plan.JsonPlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证事务开始、计划执行、提交和非法状态处理
class TransactionManagerTest {

    @Test
    void beginsExecutesAndCommitsTransaction() {
        TransactionTestFixture fixture = new TransactionTestFixture();
        fixture.authorization.grant("alice", "CREATE", "student");
        var begun = fixture.transactionManager.begin("session-a");

        var executed = fixture.transactionManager.execute(
                "req-execute",
                begun.data().txId(),
                fixture.parse(fixture.createPlan()),
                "alice"
        );
        var committed = fixture.transactionManager.commit("req-commit", begun.data().txId());

        assertTrue(executed.isOk());
        assertTrue(committed.isOk());
        assertEquals(TransactionState.COMMITTED, begun.data().state());
        assertTrue(fixture.pages.callCount("flush_all") > 0);
    }

    @Test
    void rejectsUnknownTransactionAndRepeatedCommit() {
        TransactionTestFixture fixture = new TransactionTestFixture();
        var unknown = fixture.transactionManager.requireActive(999);
        var begun = fixture.transactionManager.begin("session-a").data();
        fixture.transactionManager.commit("req-first", begun.txId());
        var repeated = fixture.transactionManager.commit("req-second", begun.txId());

        assertFalse(unknown.isOk());
        assertEquals("TX_NOT_FOUND", unknown.error().getCode());
        assertFalse(repeated.isOk());
        assertEquals("TX_NOT_ACTIVE", repeated.error().getCode());
    }

    @Test
    void resolvesAllTablesFromMultiTablePlan() {
        JsonPlanNode left = new JsonPlanNode(
                "SeqScan",
                Map.of("table", "student"),
                List.of(),
                List.of()
        );
        JsonPlanNode right = new JsonPlanNode(
                "SeqScan",
                Map.of("table", "score"),
                List.of(),
                List.of()
        );
        JsonPlanNode join = new JsonPlanNode(
                "Join",
                Map.of("leftKey", "student.id", "rightKey", "score.student_id"),
                List.of(left, right),
                List.of()
        );
        PlanActionResolver resolver = new PlanActionResolver();

        assertEquals("SELECT", resolver.resolveAction(join).data());
        assertEquals("student,score", resolver.resolveTable(join).data());
    }
}
