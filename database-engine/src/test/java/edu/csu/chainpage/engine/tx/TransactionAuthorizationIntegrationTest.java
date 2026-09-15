package edu.csu.chainpage.engine.tx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证事务执行严格遵守授权检查、加锁、执行计划的顺序
class TransactionAuthorizationIntegrationTest {

    @Test
    void checksAuthorizationBeforeTakingLockOrExecutingPlan() {
        TransactionTestFixture fixture = new TransactionTestFixture();
        long deniedTx = fixture.transactionManager.begin("denied").data().txId();

        var denied = fixture.transactionManager.execute(
                "req-denied",
                deniedTx,
                fixture.parse(fixture.createPlan()),
                "alice"
        );

        assertFalse(denied.isOk());
        assertEquals("AUTHORIZATION_DENIED", denied.error().getCode());
        assertFalse(fixture.catalogManager.containsTable("student"));
        assertTrue(fixture.lockManager.acquireWrite("verify-no-lock", 99, "student").isOk());
    }

    @Test
    void checksLockBeforeExecutingAuthorizedPlan() {
        TransactionTestFixture fixture = new TransactionTestFixture();
        fixture.authorization.grant("alice", "CREATE", "student");
        fixture.lockManager.acquireRead("prepare", 99, "student");
        long txId = fixture.transactionManager.begin("authorized").data().txId();

        var conflicted = fixture.transactionManager.execute(
                "req-conflict",
                txId,
                fixture.parse(fixture.createPlan()),
                "alice"
        );

        assertFalse(conflicted.isOk());
        assertEquals("LOCK_CONFLICT", conflicted.error().getCode());
        assertFalse(fixture.catalogManager.containsTable("student"));
    }
}
