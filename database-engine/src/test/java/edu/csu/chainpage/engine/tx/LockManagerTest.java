package edu.csu.chainpage.engine.tx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 验证表级读写锁冲突、升级和释放行为
class LockManagerTest {

    @Test
    void allowsSharedReadsAndRejectsConflictingWrite() {
        LockManager locks = new LockManager();

        assertTrue(locks.acquireRead("req-1", 1, "student").isOk());
        assertTrue(locks.acquireRead("req-2", 2, "STUDENT").isOk());
        assertFalse(locks.acquireWrite("req-3", 3, "student").isOk());
        assertTrue(locks.holdsConflict(3, "student", "WRITE"));
    }

    @Test
    void releasesAllLocksAndAllowsOwnReadToUpgrade() {
        LockManager locks = new LockManager();
        locks.acquireRead("req-1", 1, "student");

        assertTrue(locks.acquireWrite("req-2", 1, "student").isOk());
        assertFalse(locks.acquireRead("req-3", 2, "student").isOk());

        locks.releaseAll(1);

        assertTrue(locks.acquireWrite("req-4", 2, "student").isOk());
    }
}
