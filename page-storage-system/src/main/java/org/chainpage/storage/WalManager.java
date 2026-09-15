package org.chainpage.storage;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 持久化页面镜像的 UPDATE/APPLIED 记录，并在 REDO 恢复时重放未应用更新。 */
final class WalManager {
    record Update(long seq, int tx, int page, long generation, byte[] before, byte[] after) {}

    private final Path path;
    private long next = 1;
    private boolean poisoned;
    private PageManager pages;

    WalManager(Path root, PageManager p) {
        path = root.resolve("wal.log");
        pages = p;
        try {
            if (!Files.exists(path)) Files.createFile(path);
        } catch (IOException e) {
            throw io(e);
        }
        bootstrap();
    }

    synchronized long append(int tx, int page, byte[] before, byte[] after) {
        healthy();
        if (next == Long.MAX_VALUE) throw new StorageException("WAL_SEQUENCE_EXHAUSTED", "日志序号已耗尽");
        if (tx < 0) throw new StorageException("WAL_INVALID_TX", "txId 必须非负", page);
        pages.requireAllocated(page);
        walPage(before, page);
        walPage(after, page);
        long seq = next;
        Map<String, Object> r = JsonFiles.map();
        r.put("kind", "UPDATE");
        r.put("logSeq", seq);
        r.put("txId", tx);
        r.put("pageId", page);
        r.put("generation", pages.generation(page));
        r.put("before", JsonFiles.b64(before));
        r.put("after", JsonFiles.b64(after));
        appendRecord(r);
        next++;
        return seq;
    }

    synchronized void applied(long seq) {
        healthy();
        if (seq <= 0 || seq >= next)
            throw new StorageException("WAL_INVALID_SEQUENCE", "logSeq 不存在");
        appendRecord(Map.of("kind", "APPLIED", "logSeq", seq));
    }

    private void appendRecord(Object r) {
        long old;
        try {
            old = Files.size(path);
        } catch (IOException e) {
            throw io(e);
        }
        try {
            byte[] bytes = encodedRecord(r);
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                ch.position(old);
                JsonFiles.writeFully(ch, ByteBuffer.wrap(bytes));
                ch.force(true);
            }
        } catch (IOException failure) {
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                ch.truncate(old);
                ch.force(true);
            } catch (IOException cleanup) {
                poisoned = true;
                throw new StorageException("RECOVERY_REQUIRED", "WAL 写入与回滚失败", null, cleanup);
            }
            throw io(failure);
        }
    }

    synchronized Map<String, Object> recover() {
        healthy();
        Parsed p = parse();
        // 每次页面分配只保留最高已应用序号，避免重放更旧的页面镜像。
        Map<String, Long> water = new HashMap<>();
        for (long seq : p.applied) {
            Update u = p.updates.get(seq);
            water.merge(u.page + ":" + u.generation, seq, Math::max);
        }
        int replayed = 0;
        List<Long> pending = new ArrayList<>();
        for (Update u : p.updates.values()) {
            if (p.applied.contains(u.seq)) continue;
            pending.add(u.seq);
            // 已释放或重新分配的页面不再属于该日志记录。
            if (!pages.isAllocated(u.page) || pages.generation(u.page) != u.generation) continue;
            if (u.seq <= water.getOrDefault(u.page + ":" + u.generation, 0L)) continue;
            pages.writePage(u.page, u.after);
            replayed++;
        }
        if (!pending.isEmpty()) {
            pages.sync();
            for (long seq : pending) applied(seq);
        }
        Map<String, Object> out = JsonFiles.map();
        out.put("replayed", replayed);
        out.put("undone", 0);
        out.put("clean", true);
        return out;
    }

    synchronized void bootstrap() {
        Parsed p = parse();
        next = p.nextSequence;
        poisoned = false;
    }

    private record Parsed(
            LinkedHashMap<Long, Update> updates, Set<Long> applied, long nextSequence) {}

    /** 仅在页面落盘且待处理 REDO 完成后压缩日志。 */
    synchronized Map<String, Object> checkpoint() {
        healthy();
        Parsed parsed = parse();
        if (!parsed.applied.containsAll(parsed.updates.keySet()))
            throw new StorageException("WAL_CHECKPOINT_PENDING", "尚有未应用的日志");
        long before = offset();
        long sequence = parsed.nextSequence - 1;
        byte[] marker = encodedRecord(Map.of("kind", "CHECKPOINT", "logSeq", sequence));
        CrashHooks.hit("checkpoint_before_replace");
        // 原子替换保证磁盘上保留旧 WAL 或新的有效检查点记录。
        JsonFiles.replace(path, marker);
        CrashHooks.hit("checkpoint_after_replace");
        next = parsed.nextSequence;
        return Map.of(
                "checkpointSeq",
                sequence,
                "beforeBytes",
                before,
                "afterBytes",
                (long) marker.length,
                "reclaimedBytes",
                Math.max(0L, before - marker.length));
    }

    private Parsed parse() {
        truncateTail();
        LinkedHashMap<Long, Update> updates = new LinkedHashMap<>();
        Set<Long> applied = new HashSet<>();
        long expected = 1;
        try {
            int line = 0;
            int records = 0;
            for (String s : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                line++;
                if (s.isBlank()) continue;
                records++;
                JsonNode n;
                try {
                    n = JsonFiles.JSON.readTree(s);
                } catch (Exception e) {
                    throw new StorageException("WAL_CORRUPT", "WAL 第 " + line + " 行损坏");
                }
                if (n == null || !n.isObject())
                    throw new StorageException("WAL_CORRUPT", "WAL 记录必须是对象");
                if (n.has("sha256")) {
                    String checksum = n.path("sha256").asText();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) n).remove("sha256");
                    if (!checksum.equals(digest(JsonFiles.compactBytes(n))))
                        throw new StorageException("WAL_CORRUPT", "WAL 校验失败");
                }
                long seq = exactLong(n, "logSeq");
                if ("CHECKPOINT".equals(n.path("kind").asText())) {
                    if (records != 1 || seq < 0 || seq == Long.MAX_VALUE)
                        throw new StorageException("WAL_CORRUPT", "CHECKPOINT 必须是首条合法记录");
                    expected = seq + 1;
                } else if ("UPDATE".equals(n.path("kind").asText())) {
                    if (seq != expected || seq == Long.MAX_VALUE)
                        throw new StorageException("WAL_SEQUENCE_GAP", "WAL logSeq 不连续");
                    int page = exactInt(n, "pageId"), tx = exactInt(n, "txId");
                    long gen = n.has("generation") ? exactLong(n, "generation") : 0;
                    if (page < 0 || tx < 0 || gen < 0)
                        throw new StorageException("WAL_CORRUPT", "WAL 元数据非法");
                    byte[] before = JsonFiles.unb64(n.path("before").asText(), "WAL_CORRUPT"),
                            after = JsonFiles.unb64(n.path("after").asText(), "WAL_CORRUPT");
                    walPage(before, page);
                    walPage(after, page);
                    updates.put(seq, new Update(seq, tx, page, gen, before, after));
                    expected++;
                } else if ("APPLIED".equals(n.path("kind").asText())) {
                    if (!updates.containsKey(seq))
                        throw new StorageException("WAL_CORRUPT", "APPLIED 指向未知 UPDATE");
                    applied.add(seq);
                } else throw new StorageException("WAL_CORRUPT", "未知 WAL 记录类型");
            }
            return new Parsed(updates, applied, expected);
        } catch (IOException e) {
            throw io(e);
        }
    }

    private static byte[] encodedRecord(Object value) {
        com.fasterxml.jackson.databind.node.ObjectNode record = JsonFiles.JSON.valueToTree(value);
        record.put("sha256", digest(JsonFiles.compactBytes(record)));
        return (JsonFiles.compact(record) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // 只有换行结尾的记录才完整；启动时截断中断写入的尾记录。
    private void truncateTail() {
        try {
            byte[] all = Files.readAllBytes(path);
            if (all.length > 0 && all[all.length - 1] != '\n') {
                int i = all.length - 1;
                while (i >= 0 && all[i] != '\n') i--;
                try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    ch.truncate(i + 1L);
                    ch.force(true);
                }
            }
        } catch (IOException e) {
            throw io(e);
        }
    }

    private static long exactLong(JsonNode n, String k) {
        JsonNode v = n.get(k);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong())
            throw new StorageException("WAL_CORRUPT", k + " 非法");
        return v.longValue();
    }

    private static int exactInt(JsonNode n, String k) {
        long v = exactLong(n, k);
        if (v < 0 || v > Integer.MAX_VALUE) throw new StorageException("WAL_CORRUPT", k + " 非法");
        return (int) v;
    }

    synchronized long next() {
        return next;
    }

    synchronized long offset() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw io(e);
        }
    }

    synchronized void truncate(long n) {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.truncate(n);
            ch.force(true);
            bootstrap();
        } catch (IOException e) {
            throw io(e);
        }
    }

    Path path() {
        return path;
    }

    void healthy() {
        if (poisoned) throw new StorageException("RECOVERY_REQUIRED", "WAL 状态不确定");
    }

    private static void walPage(byte[] data, int page) {
        if (data == null || data.length != FileManager.PAGE_SIZE)
            throw new StorageException("WAL_INVALID_PAGE_DATA", "WAL 页面镜像必须恰好为 4096 字节", page);
    }

    private StorageException io(Exception e) {
        return new StorageException("FILE_IO_ERROR", e.getMessage(), null, e);
    }
}
