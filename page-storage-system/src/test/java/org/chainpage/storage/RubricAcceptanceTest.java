package org.chainpage.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Preset, reproducible OS acceptance cases aligned with the course rubric. */
class RubricAcceptanceTest {
    @TempDir Path dir;

    private static byte[] image(int value) {
        byte[] data = new byte[FileManager.PAGE_SIZE];
        Arrays.fill(data, (byte) value);
        return data;
    }

    @Test
    void rejectedTransactionDoesNotChangeResidentImageOrReplacementOrder() {
        try (StorageManager s = new StorageManager(dir, 2, "LRU", true)) {
            int a = s.allocatePage(), b = s.allocatePage(), c = s.allocatePage();
            s.getPage(a, null);
            s.getPage(b, null);
            Map<String, Object> before = s.stats();
            StorageException error =
                    assertThrows(StorageException.class, () -> s.writePage(a, image(9), null, -1));
            assertEquals("WAL_INVALID_TX", error.code());
            assertEquals(before, s.stats());
            s.getPage(c, null);
            assertEquals(
                    a,
                    s.events(false).stream()
                            .filter(e -> e.get("event").equals("EVICT"))
                            .findFirst()
                            .orElseThrow()
                            .get("pageId"));
            assertArrayEquals(image(0), s.getPage(a, null).data());
            s.flushAll();
            assertArrayEquals(image(0), s.readPageDirect(a));
        }
    }

    @Test
    void rejectedTransactionDoesNotInsertOrEvictANonresidentPage() {
        try (StorageManager s = new StorageManager(dir, 1, "FIFO", true)) {
            int a = s.allocatePage(), b = s.allocatePage();
            s.getPage(a, null);
            Map<String, Object> before = s.stats();
            assertThrows(StorageException.class, () -> s.writePage(b, image(8), null, -1));
            assertEquals(before, s.stats());
            assertTrue(s.getPage(a, null).hit());
            assertArrayEquals(image(0), s.readPageDirect(b));
        }
    }

    @Test
    void walFailureDoesNotPublishAnUnloggedDirtyImage() throws Exception {
        try (StorageManager s = new StorageManager(dir)) {
            int page = s.allocatePage();
            s.getPage(page, null);
            Path log = dir.resolve("wal.log"), backup = dir.resolve("wal.backup");
            Files.move(log, backup);
            try {
                assertEquals(
                        "FILE_IO_ERROR",
                        assertThrows(
                                        StorageException.class,
                                        () -> s.writePage(page, image(7), null, 0))
                                .code());
                BufferPool.Page cached = s.getPage(page, null);
                assertArrayEquals(image(0), cached.data());
                assertFalse(cached.dirty());
            } finally {
                Files.move(backup, log);
            }
            s.flushAll();
            assertArrayEquals(image(0), s.readPageDirect(page));
        }
    }

    @Test
    void fixedSequenceHasDifferentLruAndFifoVictims() {
        for (String policy : List.of("LRU", "FIFO")) {
            try (StorageManager s = new StorageManager(dir.resolve(policy), 2, policy, true)) {
                int a = s.allocatePage(), b = s.allocatePage(), c = s.allocatePage();
                for (int page : new int[] {a, b, a, c}) s.getPage(page, null);
                assertEquals(1L, s.stats().get("hits"));
                assertEquals(3L, s.stats().get("misses"));
                assertEquals(1L, s.stats().get("evictions"));
                assertEquals(
                        policy.equals("LRU") ? b : a,
                        s.events(false).stream()
                                .filter(e -> e.get("event").equals("EVICT"))
                                .findFirst()
                                .orElseThrow()
                                .get("pageId"));
            }
        }
    }

    @Test
    void dirtyVictimIsWrittenBackBeforeEviction() {
        try (StorageManager s = new StorageManager(dir, 1, "LRU", true)) {
            int a = s.allocatePage(), b = s.allocatePage();
            s.writePage(a, image(42), null, 0);
            assertArrayEquals(image(0), s.readPageDirect(a));
            s.getPage(b, null);
            assertArrayEquals(image(42), s.readPageDirect(a));
            assertEquals(
                    List.of("FLUSH", "EVICT"),
                    s.events(false).stream()
                            .map(e -> (String) e.get("event"))
                            .filter(e -> !e.equals("MISS"))
                            .toList());
        }
    }

    @Test
    void actualCliSupportsAnUpperLayerCallerAndRestart() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(image(90));
        List<Map<String, Object>> responses =
                cli(
                        List.of(
                                Map.of(
                                        "requestId",
                                        "table",
                                        "op",
                                        "create_table_pages",
                                        "table",
                                        "Student"),
                                Map.of(
                                        "requestId",
                                        "allocate",
                                        "op",
                                        "allocate_page_for_table",
                                        "table",
                                        "student"),
                                Map.of(
                                        "requestId",
                                        "write",
                                        "op",
                                        "write_page",
                                        "pageId",
                                        0,
                                        "data",
                                        encoded),
                                Map.of("requestId", "flush", "op", "flush_all")));
        for (int i = 0; i < responses.size(); i++) assertEquals(true, responses.get(i).get("ok"));
        assertEquals("write", ((Map<?, ?>) responses.get(2).get("data")).get("requestId"));
        List<Map<String, Object>> restarted =
                cli(
                        List.of(
                                Map.of("requestId", "read", "op", "get_page", "pageId", 0),
                                Map.of("op", "list_table_pages", "table", "STUDENT"),
                                Map.of("requestId", "bad", "op", "get_page", "pageId", 999)));
        Map<?, ?> read = (Map<?, ?>) restarted.get(0).get("data");
        assertEquals(encoded, read.get("data"));
        assertEquals("read", read.get("requestId"));
        assertEquals(List.of(0), ((Map<?, ?>) restarted.get(1).get("data")).get("pageIds"));
        Map<?, ?> error = (Map<?, ?>) restarted.get(2).get("error");
        assertEquals("bad", error.get("requestId"));
        assertEquals("PAGE_NOT_ALLOCATED", error.get("code"));
        assertTrue(error.containsKey("statementIndex"));
        assertNull(error.get("statementIndex"));
    }

    private List<Map<String, Object>> cli(List<Map<String, Object>> requests) throws Exception {
        Path input = Files.createTempFile(dir, "requests", ".jsonl");
        Path output = Files.createTempFile(dir, "responses", ".jsonl");
        Path errors = Files.createTempFile(dir, "stderr", ".txt");
        List<String> lines = requests.stream().map(JsonFiles::compact).toList();
        Files.write(input, lines, StandardCharsets.UTF_8);
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                StorageCli.class.getName(),
                                "--root",
                                dir.resolve("database").toString(),
                                "--capacity",
                                "2")
                        .redirectInput(input.toFile())
                        .redirectOutput(output.toFile())
                        .redirectError(errors.toFile())
                        .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI timeout");
            assertEquals(0, process.exitValue(), Files.readString(errors));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        List<Map<String, Object>> responses = new ArrayList<>();
        for (String line : Files.readAllLines(output, StandardCharsets.UTF_8))
            responses.add(
                    JsonFiles.JSON.readValue(
                            line, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        assertEquals(requests.size(), responses.size());
        return responses;
    }

    @Test
    void cacheComparisonProducesReproducibleEvidence() throws Exception {
        List<Map<String, Object>> measurements = new ArrayList<>();
        // A,B,A,C,A,B repeated: LRU retains hot page A; FIFO does not refresh it on hits.
        int[] sequence = {0, 1, 0, 2, 0, 1};
        for (String mode : List.of("DIRECT", "FIFO", "LRU")) {
            try (StorageManager s =
                    new StorageManager(
                            dir.resolve(mode), 2, mode.equals("DIRECT") ? "LRU" : mode, true)) {
                for (int i = 0; i < 3; i++) {
                    int page = s.allocatePage();
                    s.writePageDirect(page, image(page + 1));
                }
                long started = System.nanoTime();
                for (int repetition = 0; repetition < 100; repetition++)
                    for (int page : sequence) {
                        byte[] bytes =
                                mode.equals("DIRECT")
                                        ? s.readPageDirect(page)
                                        : s.getPage(page, null).data();
                        assertArrayEquals(image(page + 1), bytes);
                    }
                long elapsed = System.nanoTime() - started;
                long misses = mode.equals("DIRECT") ? 600 : (Long) s.stats().get("misses");
                long hits = mode.equals("DIRECT") ? 0 : (Long) s.stats().get("hits");
                measurements.add(
                        Map.of(
                                "mode",
                                mode,
                                "accesses",
                                600,
                                "pageFileReads",
                                misses,
                                "hits",
                                hits,
                                "elapsedNanos",
                                elapsed));
                assertEquals(
                        mode.equals("DIRECT") ? 600L : mode.equals("FIFO") ? 302L : 202L, misses);
            }
        }
        Path result = Path.of("target", "rubric-cache-comparison.json");
        Files.createDirectories(result.getParent());
        Files.write(
                result,
                JsonFiles.json(
                        Map.of(
                                "capacity",
                                2,
                                "sequence",
                                sequence,
                                "repetitions",
                                100,
                                "measurements",
                                measurements,
                                "timingNote",
                                "Elapsed time includes assertions and OS filesystem caching; no timing threshold is asserted.")));
    }
}
