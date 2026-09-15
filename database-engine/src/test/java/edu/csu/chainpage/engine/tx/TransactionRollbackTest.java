package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.executor.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证回滚能够恢复数据页前镜像和系统目录变更
class TransactionRollbackTest {

    @Test
    void rollbackRestoresPageBeforeWrite() {
        TransactionTestFixture fixture = new TransactionTestFixture();
        fixture.authorization.grant("alice", "CREATE", "student");
        fixture.authorization.grant("alice", "INSERT", "student");
        fixture.authorization.grant("alice", "SELECT", "student");
        long createTx = fixture.transactionManager.begin("create").data().txId();
        fixture.transactionManager.execute(
                "req-create", createTx, fixture.parse(fixture.createPlan()), "alice");
        fixture.transactionManager.commit("req-create-commit", createTx);

        Transaction insertTransaction = fixture.transactionManager.begin("insert").data();
        long insertTx = insertTransaction.txId();
        fixture.transactionManager.execute(
                "req-insert", insertTx, fixture.parse(fixture.insertPlan(1, "Alice")), "alice");
        var rolledBack = fixture.transactionManager.rollback("req-rollback", insertTx);

        long selectTx = fixture.transactionManager.begin("select").data().txId();
        var selected = fixture.transactionManager.execute(
                "req-select", selectTx, fixture.parse(fixture.selectPlan()), "alice");
        CommandResult result = selected.data().commandResult();
        assertTrue(rolledBack.isOk());
        assertEquals(TransactionState.ROLLED_BACK, insertTransaction.state());
        assertEquals(List.of(), result.rows());
    }

    @Test
    void rollbackRemovesCreatedTableFromCatalogAndStorage() {
        TransactionTestFixture fixture = new TransactionTestFixture();
        fixture.authorization.grant("alice", "CREATE", "student");
        Transaction transaction = fixture.transactionManager.begin("create").data();
        fixture.transactionManager.execute(
                "req-create", transaction.txId(), fixture.parse(fixture.createPlan()), "alice");

        var rolledBack = fixture.transactionManager.rollback("req-rollback", transaction.txId());

        assertTrue(rolledBack.isOk());
        assertEquals(TransactionState.ROLLED_BACK, transaction.state());
        assertFalse(fixture.catalogManager.containsTable("student"));
        assertFalse(fixture.pages.listTablePages("verify", "student").isOk());
    }
}
