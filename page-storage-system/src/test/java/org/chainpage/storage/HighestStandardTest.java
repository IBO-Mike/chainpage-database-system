package org.chainpage.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Invariants and fault boundaries required beyond the small happy-path acceptance cases. */
class HighestStandardTest {
    @TempDir Path dir;

    private static byte[] image(int value) {
        byte[] data = new byte[4096];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static Map<String, Object> rid(int key) {
        return Map.of("pageId", key, "slotId", 0);
    }

    @Test
    void clockHasSecondChanceAndHonorsCandidateSubsets() {
        ReplacementPolicy clock = ReplacementPolicy.make("CLOCK");
        for (int id : new int[] {1, 2, 3}) clock.insert(id);
        assertEquals(1, clock.victim(Set.of(1, 2, 3)));
        clock.remove(1);
        clock.access(2);
        assertEquals(3, clock.victim(Set.of(2, 3)));
        clock.remove(3);
        clock.insert(4);
        assertEquals(4, clock.victim(Set.of(4)));
        assertThrows(StorageException.class, () -> clock.victim(Set.of(99)));
        clock.remove(2);
        clock.remove(4);
        clock.insert(8);
        assertEquals(8, clock.victim(Set.of(8)));
    }

    @Test
    void directWriteRetiresDirtyFrameAndCannotBeOverwrittenByOldRedo() {
        try (StorageManager s = new StorageManager(dir)) {
            int page = s.allocatePage();
            s.writePage(page, image(1), null, 0);
            s.writePageDirect(page, image(2));
            assertArrayEquals(image(2), s.getPage(page, null).data());
            s.flushAll();
            assertArrayEquals(image(2), s.readPageDirect(page));
        }
        try (StorageManager restarted = new StorageManager(dir)) {
            assertArrayEquals(image(2), restarted.getPage(0, null).data());
        }
    }

    @Test
    void directWriteHonorsExternalReadLock() {
        try (StorageManager s = new StorageManager(dir)) {
            int page = s.allocatePage();
            s.lockPage(page, "READ", "other");
            assertEquals(
                    "PAGE_LOCK_BUSY",
                    assertThrows(StorageException.class, () -> s.writePageDirect(page, image(3)))
                            .code());
            s.unlockPage(page, "other");
            assertArrayEquals(image(0), s.readPageDirect(page));
        }
    }

    @Test
    void explicitRecoveryInvalidatesCleanCachedBytes() {
        try (StorageManager s = new StorageManager(dir)) {
            int page = s.allocatePage();
            s.getPage(page, null);
            s.appendLog(0, page, image(0), image(6));
            assertEquals(1, s.recover().get("replayed"));
            assertArrayEquals(image(6), s.getPage(page, null).data());
        }
    }

    @Test
    void checkpointReclaimsLogAndPreservesSequenceAcrossRestart() {
        long next;
        try (StorageManager s = new StorageManager(dir)) {
            int page = s.allocatePage();
            for (int i = 1; i <= 20; i++) s.writePage(page, image(i), null, 0);
            // Include an explicit update not represented by a dirty frame.
            s.flushAll();
            s.appendLog(0, page, image(20), image(21));
            Map<String, Object> result = s.checkpoint();
            assertTrue((Long) result.get("reclaimedBytes") > 200_000L);
            assertTrue((Long) result.get("afterBytes") < 256);
            assertArrayEquals(image(21), s.getPage(page, null).data());
            next = (Long) s.stats().get("nextLogSeq");
            assertEquals(22L, next);
        }
        try (StorageManager restarted = new StorageManager(dir)) {
            assertEquals(next, restarted.appendLog(0, 0, image(21), image(22)));
            restarted.recover();
            assertArrayEquals(image(22), restarted.getPage(0, null).data());
            restarted.checkpoint();
            restarted.checkpoint();
        }
    }

    @Test
    void checkpointRespectsLocksAndDoesNotCompactOnFailure() throws Exception {
        try (StorageManager s = new StorageManager(dir)) {
            int page = s.allocatePage();
            s.writePage(page, image(5), null, 0);
            byte[] before = Files.readAllBytes(dir.resolve("wal.log"));
            s.lockPage(page, "READ", "external");
            assertEquals(
                    "PAGE_LOCK_BUSY", assertThrows(StorageException.class, s::checkpoint).code());
            assertArrayEquals(before, Files.readAllBytes(dir.resolve("wal.log")));
            s.unlockPage(page, "external");
            s.checkpoint();
            assertArrayEquals(image(5), s.getPage(page, null).data());
        }
    }

    @Test
    void checkpointCrashBeforeOrAfterReplacementKeepsDataAndSequence() throws Exception {
        for (String point : List.of("checkpoint_before_replace", "checkpoint_after_replace")) {
            Path root = dir.resolve(point);
            try (StorageManager s = new StorageManager(root)) {
                int page = s.allocatePage();
                s.writePage(page, image(42), null, 0);
            }
            ProcessBuilder builder =
                    new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-cp",
                            System.getProperty(
                                    "surefire.test.class.path",
                                    System.getProperty("java.class.path")),
                            CrashHarness.class.getName(),
                            root.toString(),
                            "checkpoint");
            builder.environment().put("CHAINPAGE_CRASH_POINT", point);
            builder.redirectErrorStream(true);
            builder.redirectOutput(dir.resolve(point + ".log").toFile());
            Process child = builder.start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS));
                assertEquals(61, child.exitValue());
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
            try (StorageManager restarted = new StorageManager(root)) {
                assertArrayEquals(image(42), restarted.getPage(0, null).data());
                assertEquals(2L, restarted.appendLog(0, 0, image(42), image(43)));
            }
        }
    }

    @Test
    void checkpointMarkerAndNegativeOverflowMetadataAreStrict() throws Exception {
        Path root = dir.resolve("negative");
        try (StorageManager s = new StorageManager(root)) {
            s.allocatePage();
            s.appendLog(0, 0, image(0), image(1));
        }
        Path log = root.resolve("wal.log");
        String source = Files.readString(log);
        Files.writeString(log, source.replace("\"pageId\":0", "\"pageId\":-4294967296"));
        assertEquals(
                "WAL_CORRUPT",
                assertThrows(StorageException.class, () -> new StorageManager(root)).code());
        for (String malformed :
                List.of(
                        "null\n",
                        "{\"kind\":\"CHECKPOINT\",\"logSeq\":-1}\n",
                        "{\"kind\":\"CHECKPOINT\",\"logSeq\":0}\n{\"kind\":\"CHECKPOINT\",\"logSeq\":0}\n")) {
            Files.writeString(log, malformed);
            assertEquals(
                    "WAL_CORRUPT",
                    assertThrows(StorageException.class, () -> new StorageManager(root)).code());
        }
    }

    @Test
    void incrementalIndexMatchesTreeMapAcrossSplitsBorrowMergeAndRootCollapse() {
        TreeMap<Integer, Map<String, Object>> expected = new TreeMap<>();
        Random random = new Random(20260914L);
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 350; i++) keys.add(i);
        Collections.shuffle(keys, random);
        try (StorageManager s = new StorageManager(dir, 8, "CLOCK", true)) {
            s.createIndex(1, true, "INT");
            for (int key : keys) {
                s.indexInsert(1, key, rid(key));
                expected.put(key, rid(key));
                if (expected.size() % 25 == 0) verifyTree(s, expected);
            }
            assertTrue((Integer) s.validateIndex(1).get("height") >= 3);
            Collections.shuffle(keys, random);
            for (int i = 0; i < 200; i++) {
                int key = keys.get(i);
                s.indexDelete(1, key, null);
                expected.remove(key);
                if (i % 20 == 0) verifyTree(s, expected);
            }
        }
        try (StorageManager restarted = new StorageManager(dir, 8, "CLOCK", true)) {
            verifyTree(restarted, expected);
            for (int key : new ArrayList<>(expected.keySet())) {
                restarted.indexDelete(1, key, rid(key));
                expected.remove(key);
                if (expected.size() % 20 == 0) verifyTree(restarted, expected);
            }
            assertEquals(1, restarted.validateIndex(1).get("height"));
            assertEquals(1, restarted.stats().get("allocatedPages"));
        }
    }

    private static void verifyTree(
            StorageManager s, TreeMap<Integer, Map<String, Object>> expected) {
        s.validateIndex(1);
        assertEquals(new ArrayList<>(expected.values()), s.indexRange(1, null, null));
        assertEquals(
                new ArrayList<>(expected.subMap(50, true, 120, true).values()),
                s.indexRange(1, 50, 120));
        for (int key : new int[] {0, 50, 100, 200, 349})
            assertEquals(
                    expected.containsKey(key) ? List.of(expected.get(key)) : List.of(),
                    s.indexSearch(1, key));
    }

    @Test
    void duplicateJsonFieldsAreRejectedAndPolicySwitchingPreservesPages() {
        assertThrows(
                StorageException.class,
                () ->
                        JsonFiles.parse(
                                "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8),
                                "TEST_CORRUPT"));
        try (StorageManager s = new StorageManager(dir, 2, "LRU", true)) {
            int page = s.allocatePage();
            s.writePage(page, image(7), null, 0);
            for (String policy : List.of("CLOCK", "FIFO", "LRU")) {
                s.setPolicy(policy);
                assertArrayEquals(image(7), s.getPage(page, null).data());
            }
        }
    }

    @Test
    void indexPageCannotBeAppendedToATable() {
        try (StorageManager s = new StorageManager(dir)) {
            int root = (Integer) s.createIndex(1, true, "INT").get("rootPageId");
            s.createTablePages("t");
            assertEquals(
                    "PAGE_IN_USE",
                    assertThrows(StorageException.class, () -> s.appendTablePage("t", root))
                            .code());
            assertEquals(List.of(), s.listTablePages("t"));
            s.validateIndex(1);
        }
    }

    @Test
    void metadataRejectsNullAndFractionalRootWithoutCoercion() throws Exception {
        try (StorageManager s = new StorageManager(dir)) {
            s.createIndex(1, true, "INT");
        }
        Path metadata = dir.resolve("indexes.json");
        Files.writeString(
                metadata, "{\"1\":{\"rootPageId\":0.5,\"unique\":true,\"keyType\":\"INT\"}}");
        assertEquals(
                "INDEX_METADATA_CORRUPT",
                assertThrows(StorageException.class, () -> new StorageManager(dir)).code());
        Files.writeString(metadata, "null");
        assertEquals(
                "INDEX_METADATA_CORRUPT",
                assertThrows(StorageException.class, () -> new StorageManager(dir)).code());
    }

    @Test
    void cacheBytesMatchIndependentModelWithMixedWritesAndEvictions() {
        for (String policy : List.of("LRU", "FIFO", "CLOCK")) {
            Map<Integer, byte[]> model = new HashMap<>();
            Path root = dir.resolve(policy);
            try (StorageManager s = new StorageManager(root, 3, policy, true)) {
                for (int page = 0; page < 12; page++) model.put(s.allocatePage(), image(0));
                Random random = new Random(90417);
                for (int step = 0; step < 350; step++) {
                    int page = random.nextInt(12);
                    if (random.nextInt(4) == 0) {
                        byte[] data = image(step);
                        s.writePage(page, data, null, 0);
                        model.put(page, data);
                    } else assertArrayEquals(model.get(page), s.getPage(page, null).data());
                    assertTrue(((Number) s.stats().get("size")).intValue() <= 3);
                }
                s.checkpoint();
            }
            try (StorageManager restarted = new StorageManager(root, 3, policy, true)) {
                model.forEach(
                        (page, data) ->
                                assertArrayEquals(data, restarted.getPage(page, null).data()));
            }
        }
    }

    @Test
    void checksumDetectsStructurallyValidPageImageCorruption() throws Exception {
        try (StorageManager s = new StorageManager(dir)) {
            s.allocatePage();
            s.appendLog(0, 0, image(0), image(1));
        }
        Path log = dir.resolve("wal.log");
        String original = Files.readString(log);
        String oldImage = Base64.getEncoder().encodeToString(image(1));
        String changedImage = Base64.getEncoder().encodeToString(image(2));
        Files.writeString(log, original.replace(oldImage, changedImage));
        assertEquals(
                "WAL_CORRUPT",
                assertThrows(StorageException.class, () -> new StorageManager(dir)).code());
    }
}
