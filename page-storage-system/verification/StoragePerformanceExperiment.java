import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.chainpage.storage.StorageManager;

/** Standalone experiment runnable against either the baseline or improved OS shaded JAR. */
public final class StoragePerformanceExperiment {
    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("mode output-directory version-label");
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        List<Map<String, Object>> samples =
                switch (args[0]) {
                    case "mutation" -> mutations(output);
                    case "cache" -> caches(output);
                    case "lookup" -> lookups(output);
                    default -> throw new IllegalArgumentException(args[0]);
                };
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(
                        output.resolve("results.json").toFile(),
                        Map.of(
                                "mode",
                                args[0],
                                "version",
                                args[2],
                                "samples",
                                samples,
                                "note",
                                "Warm-up excluded; all results verified. Timings reflect one host and OS filesystem cache, not physical disk I/O."));
        System.out.println(
                "PASS " + args[0] + " " + args[2] + ": " + samples.size() + " verified samples");
    }

    private static List<Map<String, Object>> mutations(Path output) throws Exception {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int round = -1; round < 5; round++) {
            List<Integer> keys = new ArrayList<>();
            for (int key = 0; key < 128; key++) keys.add(key);
            Collections.shuffle(keys, new Random(4711));
            try (StorageManager s =
                    new StorageManager(
                            Files.createTempDirectory("chainpage-mutation-"), 8, "LRU", true)) {
                s.createIndex(1, true, "INT");
                long writes = count(s, "flushes");
                long started = System.nanoTime();
                for (int key : keys) s.indexInsert(1, key, rid(key));
                long insertNanos = System.nanoTime() - started;
                long insertWrites = count(s, "flushes") - writes;
                check(s.indexRange(1, null, null).equals(sortedRids(0, 128)), "insert result");
                s.validateIndex(1);
                Collections.sort(keys);
                writes = count(s, "flushes");
                started = System.nanoTime();
                for (int key = 0; key < 96; key++) s.indexDelete(1, key, null);
                long deleteNanos = System.nanoTime() - started;
                long deleteWrites = count(s, "flushes") - writes;
                check(s.indexRange(1, null, null).equals(sortedRids(96, 128)), "delete result");
                s.validateIndex(1);
                if (round >= 0)
                    samples.add(
                            Map.of(
                                    "round",
                                    round,
                                    "insertCount",
                                    128,
                                    "deleteCount",
                                    96,
                                    "insertNanos",
                                    insertNanos,
                                    "deleteNanos",
                                    deleteNanos,
                                    "insertPageWrites",
                                    insertWrites,
                                    "deletePageWrites",
                                    deleteWrites));
            }
        }
        return samples;
    }

    private static List<Map<String, Object>> caches(Path output) {
        Map<String, int[]> workloads = new LinkedHashMap<>();
        workloads.put("hotspot", sequence("hotspot"));
        workloads.put("sequential", sequence("sequential"));
        workloads.put("random", sequence("random"));
        workloads.put("working-set", sequence("working-set"));
        List<Map<String, Object>> samples = new ArrayList<>();
        for (var workload : workloads.entrySet()) {
            for (String mode : List.of("DIRECT", "FIFO", "LRU", "CLOCK")) {
                for (int round = -1; round < 3; round++) {
                    try (StorageManager s =
                            new StorageManager(
                                    output.resolve(workload.getKey() + "-" + mode + "-" + round),
                                    8,
                                    mode.equals("DIRECT") ? "LRU" : mode,
                                    true)) {
                        for (int page = 0; page < 32; page++) {
                            check(s.allocatePage() == page, "allocation");
                            s.writePageDirect(page, image(page));
                        }
                        long misses = count(s, "misses"), hits = count(s, "hits");
                        long started = System.nanoTime();
                        for (int page : workload.getValue()) {
                            byte[] data =
                                    mode.equals("DIRECT")
                                            ? s.readPageDirect(page)
                                            : s.getPage(page, null).data();
                            check(Arrays.equals(data, image(page)), "cache data");
                        }
                        long nanos = System.nanoTime() - started;
                        if (round >= 0)
                            samples.add(
                                    Map.of(
                                            "workload",
                                            workload.getKey(),
                                            "mode",
                                            mode,
                                            "round",
                                            round,
                                            "accesses",
                                            workload.getValue().length,
                                            "nanos",
                                            nanos,
                                            "hits",
                                            count(s, "hits") - hits,
                                            "pageFileReads",
                                            mode.equals("DIRECT")
                                                    ? (long) workload.getValue().length
                                                    : count(s, "misses") - misses));
                    }
                }
            }
        }
        return samples;
    }

    private static int[] sequence(String name) {
        int[] sequence = new int[1024];
        Random random = new Random(20260914);
        for (int i = 0; i < sequence.length; i++)
            sequence[i] =
                    switch (name) {
                        case "hotspot" -> i % 10 < 8 ? random.nextInt(4) : 4 + random.nextInt(28);
                        case "sequential" -> i % 32;
                        case "random" -> random.nextInt(32);
                        case "working-set" -> i % 6;
                        default -> throw new AssertionError();
                    };
        return sequence;
    }

    private static List<Map<String, Object>> lookups(Path output) {
        List<Map<String, Object>> samples = new ArrayList<>();
        Path root = output.resolve("database");
        try (StorageManager s = new StorageManager(root, 8, "LRU", true)) {
            s.createTablePages("rows");
            s.createIndex(1, true, "INT");
            int page = s.allocatePageForTable("rows").get(0);
            for (int key = 0; key < 256; key++) {
                Map<String, Object> row = Map.of("id", key, "label", "x".repeat(180));
                Map<String, Object> inserted;
                try {
                    inserted = s.insertRecord(page, row, null, 0);
                } catch (org.chainpage.storage.StorageException e) {
                    if (!e.code().equals("PAGE_NO_SPACE")) throw e;
                    List<Integer> pages = s.allocatePageForTable("rows");
                    page = pages.get(pages.size() - 1);
                    inserted = s.insertRecord(page, row, null, 0);
                }
                s.indexInsert(1, key, Map.of("pageId", page, "slotId", inserted.get("slotId")));
            }
        }
        for (String mode : List.of("scan", "index")) {
            for (int round = -1; round < 3; round++) {
                try (StorageManager s = new StorageManager(root, 8, "LRU", true)) {
                    List<Integer> tablePages = s.listTablePages("rows");
                    long before = count(s, "misses");
                    long started = System.nanoTime();
                    for (int query = 0; query < 64; query++) {
                        int key = (query * 71) % 256;
                        List<Map<String, Object>> matches = new ArrayList<>();
                        if (mode.equals("index")) {
                            for (Map<String, Object> rowId : s.indexSearch(1, key))
                                matches.add(
                                        (Map<String, Object>)
                                                s.readRecord(
                                                                (Integer) rowId.get("pageId"),
                                                                (Integer) rowId.get("slotId"),
                                                                null)
                                                        .get("row"));
                        } else {
                            for (int page : tablePages)
                                for (Map<String, Object> record : s.scanRecords(page, null)) {
                                    Map<String, Object> row =
                                            (Map<String, Object>) record.get("row");
                                    if (row.get("id").equals(key)) matches.add(row);
                                }
                        }
                        check(
                                matches.equals(
                                        List.of(Map.of("id", key, "label", "x".repeat(180)))),
                                "lookup full row");
                    }
                    if (round >= 0)
                        samples.add(
                                Map.of(
                                        "mode",
                                        mode,
                                        "round",
                                        round,
                                        "queries",
                                        64,
                                        "nanos",
                                        System.nanoTime() - started,
                                        "pageFileReads",
                                        count(s, "misses") - before));
                }
            }
        }
        return samples;
    }

    private static Map<String, Object> rid(int key) {
        return Map.of("pageId", key, "slotId", 0);
    }

    private static List<Map<String, Object>> sortedRids(int start, int end) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = start; i < end; i++) rows.add(rid(i));
        return rows;
    }

    private static long count(StorageManager s, String name) {
        return ((Number) s.stats().get(name)).longValue();
    }

    private static byte[] image(int value) {
        byte[] data = new byte[4096];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static void check(boolean valid, String label) {
        if (!valid) throw new AssertionError(label);
    }
}
