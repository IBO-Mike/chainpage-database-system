package org.chainpage.storage;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/** Public storage facade that wires page I/O, caching, records, indexes and recovery together. */
public final class StorageManager implements AutoCloseable {
    private final Path root;
    private final DirectoryLease lease;
    private final ReentrantLock guard = new ReentrantLock();
    private final PageManager pages;
    private final WalManager wal;
    private final LockManager locks;
    private final BufferPool buffer;
    private final TablePageMap tables;
    private final BPlusTree indexes;
    private final AtomicCoordinator atomic;
    private ReplacementPolicy policyProbe;
    private final Map<String, Object> lastRecovery;
    private final boolean recoveredOperation;

    public StorageManager(Path root) {
        this(root, 16, "LRU", true);
    }

    public StorageManager(Path root, int capacity, String policy, boolean recover) {
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new StorageException("FILE_IO_ERROR", e.getMessage(), null, e);
        }
        this.root = root;
        DirectoryLease acquired = new DirectoryLease(root);
        lease = acquired;
        lease.attach(guard);
        try {
            // Roll back incomplete compound operations before loading metadata and replaying WAL.
            recoveredOperation = AtomicCoordinator.restore(root);
            pages = new PageManager(root);
            wal = new WalManager(root, pages);
            lastRecovery =
                    recover ? wal.recover() : Map.of("replayed", 0, "undone", 0, "clean", true);
            locks = new LockManager();
            buffer = new BufferPool(pages, wal, locks, capacity, policy);
            tables = new TablePageMap(root, pages);
            indexes = new BPlusTree(root, pages, buffer);
            indexes.validateDisjoint(tables);
            atomic = new AtomicCoordinator(root, pages, buffer, wal, tables, indexes);
            policyProbe = ReplacementPolicy.make(policy);
        } catch (RuntimeException | Error e) {
            acquired.close();
            throw e;
        }
    }

    // Serialize facade calls; the reentrant guard also permits nested calls by compound operations.
    private <T> T locked(Callable<T> c) {
        lease.check();
        if (!guard.tryLock()) throw new StorageException("PAGE_LOCK_BUSY", "存储操作正在进行，请重试");
        try {
            wal.healthy();
            atomic.healthy();
            return c.call();
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("STORAGE_INTERNAL_ERROR", e.getMessage(), null, e);
        } finally {
            guard.unlock();
        }
    }

    // Add a durable rollback boundary for operations spanning multiple storage files.
    private <T> T atom(Callable<T> c) {
        return locked(() -> atomic.operation(c));
    }

    public int allocatePage() {
        return locked(pages::allocatePage);
    }

    public void freePage(int id) {
        locked(
                () -> {
                    if (tables.owns(id) || indexes.owns(id))
                        throw new StorageException("PAGE_IN_USE", "页仍被表或索引引用", id);
                    pages.freePage(id);
                    return null;
                });
    }

    public byte[] readPageDirect(int id) {
        return locked(() -> pages.readPage(id));
    }

    public void writePageDirect(int id, byte[] data) {
        locked(
                () -> {
                    pages.writePage(id, data);
                    return null;
                });
    }

    public void sync() {
        locked(
                () -> {
                    pages.sync();
                    return null;
                });
    }

    public BufferPool.Page getPage(int id, String owner) {
        return locked(() -> buffer.getPage(id, owner));
    }

    public void writePage(int id, byte[] data, String owner, int tx) {
        locked(
                () -> {
                    buffer.putPage(id, data, true, owner, tx);
                    return null;
                });
    }

    public BufferPool.Put putPage(int id, byte[] data, boolean dirty, String owner, int tx) {
        return locked(() -> buffer.putPage(id, data, dirty, owner, tx));
    }

    public List<Integer> flushAll() {
        return locked(buffer::flushAll);
    }

    public void flushPage(int id, String owner) {
        locked(
                () -> {
                    buffer.flushPage(id, owner);
                    return null;
                });
    }

    public List<Integer> createTablePages(String t) {
        return atom(() -> tables.create(t));
    }

    public List<Integer> allocatePageForTable(String t) {
        return atom(
                () -> {
                    tables.list(t);
                    int id = pages.allocatePage();
                    tables.append(t, id);
                    return tables.list(t);
                });
    }

    public List<Integer> appendTablePage(String t, int id) {
        return atom(
                () -> {
                    pages.requireAllocated(id);
                    return tables.append(t, id);
                });
    }

    public List<Integer> listTablePages(String t) {
        return locked(() -> tables.list(t));
    }

    public List<Integer> dropTablePages(String t) {
        return atom(
                () -> {
                    List<Integer> ids = tables.remove(t);
                    for (int id : ids) pages.freePage(id);
                    return ids;
                });
    }

    public Map<String, Object> insertRecord(int id, Map<String, Object> row, String owner, int tx) {
        return locked(
                () -> {
                    String o = owner == null ? "record-" + Thread.currentThread().getId() : owner;
                    try (LockManager.Lease ignored = locks.acquire(id, "WRITE", o)) {
                        SlottedPage p = SlottedPage.from(buffer.getPage(id, o).data());
                        SlottedPage.Insert x = p.insert(row);
                        buffer.putPage(id, p.bytes(), true, o, tx);
                        return Map.of(
                                "pageId", id, "slotId", x.slotId(), "freeBytes", x.freeBytes());
                    }
                });
    }

    public Map<String, Object> readRecord(int id, int slot, String owner) {
        return locked(
                () ->
                        Map.of(
                                "row",
                                SlottedPage.from(buffer.getPage(id, owner).data()).read(slot),
                                "deleted",
                                false));
    }

    public Map<String, Object> deleteRecord(int id, int slot, String owner, int tx) {
        return locked(
                () -> {
                    String o = owner == null ? "record-" + Thread.currentThread().getId() : owner;
                    try (LockManager.Lease ignored = locks.acquire(id, "WRITE", o)) {
                        SlottedPage p = SlottedPage.from(buffer.getPage(id, o).data());
                        p.delete(slot);
                        buffer.putPage(id, p.bytes(), true, o, tx);
                        return Map.of("pageId", id, "slotId", slot, "deleted", true);
                    }
                });
    }

    public int deleteRows(String table, List<Map<String, Object>> rowIds, String owner, int tx) {
        return atom(
                () -> {
                    Set<Integer> tablePages = new HashSet<>(tables.list(table));
                    Set<String> seen = new HashSet<>();
                    for (Map<String, Object> rowId : rowIds) {
                        if (rowId.size() != 2
                                || !(rowId.get("pageId") instanceof Integer page)
                                || !(rowId.get("slotId") instanceof Integer slot)
                                || page < 0
                                || slot < 0)
                            throw new StorageException("INVALID_REQUEST", "rowIds 中的 RowId 非法");
                        if (!tablePages.contains(page))
                            throw new StorageException("ROWID_NOT_IN_TABLE", "RowId 不属于指定表", page);
                        if (!seen.add(page + ":" + slot))
                            throw new StorageException("INVALID_REQUEST", "rowIds 含重复 RowId", page);
                    }
                    for (Map<String, Object> rowId : rowIds)
                        deleteRecord(
                                (Integer) rowId.get("pageId"),
                                (Integer) rowId.get("slotId"),
                                owner,
                                tx);
                    return rowIds.size();
                });
    }

    public List<Map<String, Object>> scanRecords(int id, String owner) {
        return locked(
                () ->
                        SlottedPage.from(buffer.getPage(id, owner).data()).records().stream()
                                .map(
                                        r ->
                                                Map.<String, Object>of(
                                                        "rowId",
                                                        Map.of("pageId", id, "slotId", r.slotId()),
                                                        "row",
                                                        r.row()))
                                .toList());
    }

    public Map<String, Object> createIndex(int id, boolean unique, String type) {
        return atom(() -> indexes.create(id, unique, type));
    }

    public Map<String, Object> dropIndex(int id) {
        return atom(() -> indexes.drop(id));
    }

    public Map<String, Object> indexInsert(int id, Object key, Map<String, Object> rid) {
        return atom(() -> indexes.insert(id, key, rid));
    }

    public Map<String, Object> indexDelete(int id, Object key, Map<String, Object> rid) {
        return atom(() -> indexes.delete(id, key, rid));
    }

    public List<Map<String, Object>> indexSearch(int id, Object key) {
        return locked(() -> indexes.search(id, key));
    }

    public List<Map<String, Object>> indexRange(int id, Object a, Object b) {
        return locked(() -> indexes.range(id, a, b));
    }

    public long appendLog(int tx, int page, byte[] before, byte[] after) {
        return locked(() -> wal.append(tx, page, before, after));
    }

    public Map<String, Object> recover() {
        return locked(wal::recover);
    }

    public Map<String, Object> lockPage(int id, String mode, String owner) {
        return locked(
                () -> {
                    pages.requireAllocated(id);
                    return locks.lockPage(id, mode, owner);
                });
    }

    public Map<String, Object> unlockPage(int id, String owner) {
        return locked(() -> locks.unlockPage(id, owner));
    }

    public Map<String, Object> stats() {
        return locked(
                () -> {
                    Map<String, Object> m = new LinkedHashMap<>(buffer.stats());
                    m.put("allocatedPages", pages.allocatedCount());
                    return m;
                });
    }

    public Map<String, Object> setPolicy(String p) {
        return locked(
                () -> {
                    Map<String, Object> result = buffer.setPolicy(p);
                    policyProbe = ReplacementPolicy.make(p);
                    return result;
                });
    }

    public List<Map<String, Object>> events(boolean clear) {
        return locked(() -> buffer.events(clear));
    }

    public Map<String, Object> policyInsert(int id) {
        return locked(
                () -> {
                    policyProbe.insert(id);
                    return Map.of("recorded", true);
                });
    }

    public Map<String, Object> policyAccess(int id) {
        return locked(
                () -> {
                    policyProbe.access(id);
                    return Map.of("recorded", true);
                });
    }

    public int policyVictim(Set<Integer> ids) {
        return locked(() -> policyProbe.victim(ids));
    }

    public Map<String, Object> validateIndex(int id) {
        return locked(
                () -> {
                    BPlusTree.Stats x = indexes.validate(id);
                    return Map.of("height", x.height(), "nodes", x.nodes(), "leaves", x.leaves());
                });
    }

    public Map<String, Object> lastRecovery() {
        return lastRecovery;
    }

    public boolean recoveredOperation() {
        return recoveredOperation;
    }

    public void close() {
        if (!guard.tryLock()) throw new StorageException("PAGE_LOCK_BUSY", "存储操作正在进行");
        try {
            if (!isClosed()) {
                buffer.flushAll();
                lease.close();
            }
        } finally {
            guard.unlock();
        }
    }

    private boolean isClosed() {
        try {
            lease.check();
            return false;
        } catch (StorageException e) {
            return true;
        }
    }
}
