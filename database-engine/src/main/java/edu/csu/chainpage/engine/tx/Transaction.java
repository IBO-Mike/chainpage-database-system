package edu.csu.chainpage.engine.tx;

import java.util.Objects;

// 表示一个由指定会话创建的数据库事务
public final class Transaction {

    private final long txId; // 事务唯一编号
    private final String session; // 创建事务的会话
    private TransactionState state = TransactionState.ACTIVE; // 当前事务状态

    // 创建一个活动事务
    public Transaction(long txId, String session) {
        if (txId <= 0) {
            throw new IllegalArgumentException("txId must be positive");
        }
        this.txId = txId;
        this.session = Objects.requireNonNull(session, "session cannot be null");
        if (session.isBlank()) {
            throw new IllegalArgumentException("session cannot be blank");
        }
    }

    // 返回事务编号
    public long txId() {
        return txId;
    }

    // 返回事务所属会话
    public String session() {
        return session;
    }

    // 返回事务当前状态
    public synchronized TransactionState state() {
        return state;
    }

    // 把活动事务标记为已经提交
    public synchronized void markCommitted() {
        requireActive();
        state = TransactionState.COMMITTED;
    }

    // 把活动事务标记为已经回滚
    public synchronized void markRolledBack() {
        requireActive();
        state = TransactionState.ROLLED_BACK;
    }

    // 确保结束状态不能被重复改写
    private void requireActive() {
        if (state != TransactionState.ACTIVE) {
            throw new IllegalStateException("transaction is not active");
        }
    }
}
