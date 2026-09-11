package edu.csu.chainpage.engine.tx;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 管理事务持有的表级读锁和写锁
public final class LockManager {

    private final Map<String, Set<Long>> readers = new HashMap<>(); // 每张表的读锁事务
    private final Map<String, Long> writers = new HashMap<>(); // 每张表的写锁事务

    // 为事务获取一张表的读锁
    public synchronized DbResult<Void> acquireRead(String requestId, long txId, String table) {
        String normalizedTable = normalizeTable(table);
        if (txId <= 0 || normalizedTable == null) {
            return failure(requestId, "LOCK_INVALID_REQUEST", "读锁参数不合法");
        }
        if (holdsConflict(txId, normalizedTable, "READ")) {
            return failure(requestId, "LOCK_CONFLICT", "表存在冲突写锁：" + normalizedTable);
        }
        readers.computeIfAbsent(normalizedTable, ignored -> new HashSet<>()).add(txId);
        return DbResult.ok(null);
    }

    // 为事务获取一张表的写锁
    public synchronized DbResult<Void> acquireWrite(String requestId, long txId, String table) {
        String normalizedTable = normalizeTable(table);
        if (txId <= 0 || normalizedTable == null) {
            return failure(requestId, "LOCK_INVALID_REQUEST", "写锁参数不合法");
        }
        if (holdsConflict(txId, normalizedTable, "WRITE")) {
            return failure(requestId, "LOCK_CONFLICT", "表存在冲突读写锁：" + normalizedTable);
        }
        writers.put(normalizedTable, txId);
        Set<Long> tableReaders = readers.get(normalizedTable);
        if (tableReaders != null) {
            tableReaders.remove(txId);
            if (tableReaders.isEmpty()) {
                readers.remove(normalizedTable);
            }
        }
        return DbResult.ok(null);
    }

    // 释放指定事务持有的全部表锁
    public synchronized void releaseAll(long txId) {
        writers.entrySet().removeIf(entry -> entry.getValue() == txId);
        readers.values().forEach(transactions -> transactions.remove(txId));
        readers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    // 判断指定事务申请的锁是否会与其他事务冲突
    public synchronized boolean holdsConflict(long txId, String table, String mode) {
        String normalizedTable = normalizeTable(table);
        if (normalizedTable == null || mode == null) {
            return true;
        }
        Long writer = writers.get(normalizedTable);
        if (writer != null && writer != txId) {
            return true;
        }
        if ("READ".equalsIgnoreCase(mode)) {
            return false;
        }
        if (!"WRITE".equalsIgnoreCase(mode)) {
            return true;
        }
        Set<Long> tableReaders = readers.getOrDefault(normalizedTable, Set.of());
        return tableReaders.stream().anyMatch(reader -> reader != txId);
    }

    // 规范化并校验表名
    private String normalizeTable(String table) {
        return table == null || table.isBlank() ? null : table.toLowerCase(Locale.ROOT);
    }

    // 创建锁管理阶段错误
    private <T> DbResult<T> failure(String requestId, String code, String message) {
        return DbResult.fail(new DbError(
                requestId,
                null,
                "LOCK",
                code,
                message,
                null,
                null,
                null
        ));
    }
}
