package edu.csu.chainpage.engine.tx;

// 表示事务当前所处的生命周期状态
public enum TransactionState {
    ACTIVE,
    COMMITTED,
    ROLLED_BACK
}
