package org.chainpage.storage;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;

/** 使用持久化前镜像日志协调多文件操作和失败回滚。 */
final class AtomicCoordinator {
    static final List<String> FILES =
            List.of("pages.dat", "page_allocation.json", "table_pages.json", "indexes.json");
    private final Path root, journal;
    private final PageManager pages;
    private final BufferPool buffer;
    private final WalManager wal;
    private final TablePageMap tables;
    private final BPlusTree tree;
    private boolean active, poisoned;

    AtomicCoordinator(
            Path root,
            PageManager pages,
            BufferPool buffer,
            WalManager wal,
            TablePageMap tables,
            BPlusTree tree) {
        this.root = root;
        this.journal = root.resolve("operation.undo");
        this.pages = pages;
        this.buffer = buffer;
        this.wal = wal;
        this.tables = tables;
        this.tree = tree;
    }

    <T> T operation(Callable<T> task) {
        healthy();
        // 嵌套操作共用最外层快照和提交边界。
        if (active)
            try {
                return task.call();
            } catch (StorageException e) {
                throw e;
            } catch (Exception e) {
                throw new StorageException("STORAGE_INTERNAL_ERROR", e.getMessage(), null, e);
            }
        // 执行任务前保存各文件前镜像和当前 WAL 偏移。
        buffer.flushAll();
        Map<String, Object> state = JsonFiles.map();
        state.put("version", 1);
        state.put("walOffset", wal.offset());
        Map<String, String> files = new LinkedHashMap<>();
        try {
            for (String n : FILES) files.put(n, JsonFiles.b64(Files.readAllBytes(root.resolve(n))));
        } catch (IOException e) {
            throw new StorageException("FILE_IO_ERROR", e.getMessage(), null, e);
        }
        state.put("files", files);
        byte[] payload = JsonFiles.json(state);
        JsonFiles.replace(
                journal,
                JsonFiles.json(
                        Map.of("payload", JsonFiles.b64(payload), "sha256", hex(sha(payload)))));
        CrashHooks.hit("journal_durable");
        active = true;
        boolean committed = false;
        try {
            T out = task.call();
            buffer.flushAll();
            CrashHooks.hit("before_commit");
            // 脏页全部落盘后删除 undo 日志，表示操作已经提交。
            Files.delete(journal);
            committed = true;
            JsonFiles.forceDirectory(root);
            return out;
        } catch (Throwable e) {
            if (committed) {
                poisoned = true;
                throw new StorageException("COMMIT_UNCERTAIN", "提交状态不确定", null, e);
            }
            try {
                restore(root);
                reload();
            } catch (Throwable recovery) {
                poisoned = true;
                throw new StorageException("RECOVERY_REQUIRED", "操作和恢复均失败", null, recovery);
            }
            if (e instanceof StorageException x) throw x;
            if (e instanceof Error x) throw x;
            throw new StorageException("STORAGE_INTERNAL_ERROR", e.getMessage(), null, e);
        } finally {
            active = false;
        }
    }

    void healthy() {
        if (poisoned) throw new StorageException("RECOVERY_REQUIRED", "恢复未完成");
    }

    private void reload() {
        pages.load();
        tables.load();
        // 索引重新加载前清空失败操作留下的缓存帧，确保读取恢复后的磁盘页。
        buffer.clear();
        tree.load();
        wal.bootstrap();
        buffer.clear();
    }

    static boolean restore(Path root) {
        Path journal = root.resolve("operation.undo");
        if (!Files.exists(journal)) return false;
        try {
            JsonNode e = JsonFiles.JSON.readTree(Files.readAllBytes(journal));
            byte[] payload =
                    JsonFiles.unb64(e.path("payload").asText(), "RECOVERY_JOURNAL_CORRUPT");
            if (!hex(sha(payload)).equals(e.path("sha256").asText()))
                throw new StorageException("RECOVERY_JOURNAL_CORRUPT", "校验失败");
            JsonNode s = JsonFiles.JSON.readTree(payload);
            if (!s.path("version").isInt() || s.path("version").intValue() != 1)
                throw new Exception("version");
            JsonNode offsetNode = s.get("walOffset");
            if (offsetNode == null
                    || !offsetNode.isIntegralNumber()
                    || !offsetNode.canConvertToLong()
                    || offsetNode.longValue() < 0) throw new Exception("walOffset");
            long offset = offsetNode.longValue();
            JsonNode fileNode = s.get("files");
            if (fileNode == null || !fileNode.isObject()) throw new Exception("files");
            Map<String, byte[]> restored = new LinkedHashMap<>();
            for (String n : FILES) {
                JsonNode value = fileNode.get(n);
                if (value == null || !value.isTextual()) throw new Exception("file " + n);
                restored.put(n, JsonFiles.unb64(value.textValue(), "RECOVERY_JOURNAL_CORRUPT"));
            }
            if (!Files.exists(root.resolve("wal.log"))
                    || Files.size(root.resolve("wal.log")) < offset)
                throw new Exception("walOffset exceeds WAL length");
            // 先完整校验日志再替换文件，避免后续坏记录造成部分恢复。
            for (var entry : restored.entrySet()) {
                JsonFiles.replace(root.resolve(entry.getKey()), entry.getValue());
                CrashHooks.hit("during_restore");
            }
            try (FileChannel ch =
                    FileChannel.open(root.resolve("wal.log"), StandardOpenOption.WRITE)) {
                // 丢弃本次失败操作在快照之后追加的 WAL 记录。
                ch.truncate(offset);
                ch.force(true);
            }
            Files.delete(journal);
            JsonFiles.forceDirectory(root);
            return true;
        } catch (StorageException x) {
            throw x;
        } catch (Exception x) {
            throw new StorageException("RECOVERY_JOURNAL_CORRUPT", x.getMessage(), null, x);
        }
    }

    private static byte[] sha(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
