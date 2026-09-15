package org.chainpage.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageSystemTest {
    @TempDir Path dir;

    static byte[] page(int n) {
        byte[] b = new byte[4096];
        Arrays.fill(b, (byte) n);
        return b;
    }

    static Map<String, Object> rid(int n) {
        return Map.of("pageId", n, "slotId", 0);
    }

    @Test
    void allocationFreeReuseAndRestart() {
        StorageManager s = new StorageManager(dir);
        int a = s.allocatePage(), b = s.allocatePage();
        assertEquals(List.of(0, 1), List.of(a, b));
        s.writePage(b, page(7), null, 0);
        s.flushAll();
        s.freePage(a);
        StorageManager r = new StorageManager(dir);
        assertEquals(0, r.allocatePage());
        assertArrayEquals(page(0), r.getPage(0, null).data());
        assertArrayEquals(page(7), r.getPage(1, null).data());
        assertThrows(StorageException.class, () -> r.getPage(99, null));
    }

    @Test
    void bufferLruDirtyAndStats() {
        StorageManager s = new StorageManager(dir, 2, "LRU", true);
        int a = s.allocatePage(), b = s.allocatePage(), c = s.allocatePage();
        assertFalse(s.getPage(a, null).hit());
        s.getPage(b, null);
        assertTrue(s.getPage(a, null).hit());
        s.writePage(b, page(8), null, 0);
        s.getPage(a, null);
        s.getPage(c, null);
        Map<String, Object> x = s.stats();
        assertEquals(2L, x.get("hits"));
        assertEquals(3L, x.get("misses"));
        assertEquals(1L, x.get("evictions"));
        assertEquals(1L, x.get("flushes"));
        assertArrayEquals(page(8), new PageManager(dir).readPage(b));
    }

    @Test
    void tableLifecyclePersists() {
        StorageManager s = new StorageManager(dir);
        assertEquals(List.of(), s.createTablePages("Student"));
        List<Integer> ids = s.allocatePageForTable("student");
        assertEquals(List.of(0), ids);
        s.writePage(0, page(4), null, 0);
        s.flushAll();
        StorageManager r = new StorageManager(dir);
        assertEquals(ids, r.listTablePages("STUDENT"));
        assertEquals(ids, r.dropTablePages("student"));
        assertEquals(0, r.stats().get("allocatedPages"));
    }

    @Test
    void jsonContract() {
        StorageManager s = new StorageManager(dir);
        Map<String, Object> a =
                StorageCli.handle(s, Map.of("requestId", "r1", "op", "allocate_page"));
        assertEquals(true, a.get("ok"));
        Map<String, Object> bad =
                StorageCli.handle(
                        s, Map.of("requestId", "r2", "op", "get_page", "pageId", 999, "extra", 1));
        assertEquals(false, bad.get("ok"));
        Map<?, ?> e = (Map<?, ?>) bad.get("error");
        assertEquals("r2", e.get("requestId"));
        assertEquals("INVALID_REQUEST", e.get("code"));
    }

    @Test
    void slottedRecordsPersist() {
        StorageManager s = new StorageManager(dir);
        int p = s.allocatePage();
        for (int i = 0; i < 30; i++) s.insertRecord(p, Map.of("id", i, "name", "row" + i), null, 0);
        assertEquals(30, s.scanRecords(p, null).size());
        s.deleteRecord(p, 3, null, 0);
        StorageException e = assertThrows(StorageException.class, () -> s.readRecord(p, 3, null));
        assertEquals("PAGE_SLOT_DELETED", e.code());
        s.flushAll();
        StorageManager r = new StorageManager(dir);
        assertEquals(29, r.scanRecords(p, null).size());
    }

    @Test
    void bplusSplitRangeDeleteAndRestart() {
        StorageManager s = new StorageManager(dir, 8, "LRU", true);
        s.createIndex(1, true, "INT");
        for (int i = 0; i < 100; i++) s.indexInsert(1, i, rid(i));
        assertEquals(List.of(rid(42)), s.indexSearch(1, 42));
        assertEquals(11, s.indexRange(1, 10, 20).size());
        for (int i = 0; i < 90; i++) s.indexDelete(1, i, null);
        StorageManager r = new StorageManager(dir);
        assertEquals(List.of(rid(99)), r.indexSearch(1, 99));
        assertEquals(10, r.indexRange(1, null, null).size());
    }

    @Test
    void variableKeysAndNonUnique() {
        StorageManager s = new StorageManager(dir);
        s.createIndex(1, false, "VARCHAR");
        for (int i = 0; i < 40; i++)
            s.indexInsert(1, String.format("%03d", i) + "字".repeat(100), rid(i));
        s.indexInsert(1, "same", rid(100));
        s.indexInsert(1, "same", rid(101));
        assertEquals(2, s.indexSearch(1, "same").size());
        assertEquals(42, s.indexRange(1, null, null).size());
    }

    @Test
    void redoAfterUnflushedWrite() {
        StorageManager s = new StorageManager(dir);
        int p = s.allocatePage();
        s.writePage(p, page(33), null, 7);
        assertArrayEquals(page(0), new PageManager(dir).readPage(p));
        StorageManager r = new StorageManager(dir);
        assertArrayEquals(page(33), r.getPage(p, null).data());
        assertEquals(1, r.lastRecovery().get("replayed"));
    }

    @Test
    void staleRedoCannotOverwriteNewerApplied() {
        StorageManager s = new StorageManager(dir);
        int p = s.allocatePage();
        s.appendLog(1, p, page(0), page(1));
        s.writePage(p, page(2), null, 2);
        s.flushAll();
        assertArrayEquals(page(2), new StorageManager(dir).getPage(p, null).data());
    }

    @Test
    void locksShareAndExclude() {
        StorageManager s = new StorageManager(dir);
        int p = s.allocatePage();
        assertEquals(true, s.lockPage(p, "READ", "a").get("granted"));
        assertEquals(true, s.lockPage(p, "READ", "b").get("granted"));
        assertEquals(false, s.lockPage(p, "WRITE", "c").get("granted"));
        assertThrows(StorageException.class, () -> s.unlockPage(p, "x"));
        s.unlockPage(p, "a");
        s.unlockPage(p, "b");
        assertEquals(true, s.lockPage(p, "WRITE", "c").get("granted"));
    }

    @Test
    void concurrentWalSequencesAreUnique() throws Exception {
        StorageManager s = new StorageManager(dir);
        int p = s.allocatePage();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            int k = i;
            fs.add(
                    pool.submit(
                            () -> {
                                go.await();
                                while (true)
                                    try {
                                        return s.appendLog(k, p, page(0), page(k));
                                    } catch (StorageException e) {
                                        if (!e.code().equals("PAGE_LOCK_BUSY")) throw e;
                                        Thread.yield();
                                    }
                            }));
        }
        go.countDown();
        Set<Long> seqs = new TreeSet<>();
        for (Future<Long> f : fs) seqs.add(f.get());
        pool.shutdown();
        assertEquals(80, seqs.size());
        assertEquals(1L, seqs.iterator().next());
        assertEquals(80L, seqs.stream().reduce((a, b) -> b).orElseThrow());
    }
}
