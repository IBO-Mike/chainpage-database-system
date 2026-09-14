package org.chainpage.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.Map;

/** Shared JSON encoding and file replacement helpers for persistent metadata and journals. */
final class JsonFiles {
    static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .STRICT_DUPLICATE_DETECTION);

    private JsonFiles() {}

    static byte[] json(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new StorageException("STORAGE_INTERNAL_ERROR", e.getMessage(), null, e);
        }
    }

    static JsonNode parse(byte[] value, String code) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (parsed == null || parsed.isNull()) throw new StorageException(code, "JSON 数据不能为空");
            return parsed;
        } catch (IOException e) {
            throw new StorageException(code, e.getMessage(), null, e);
        }
    }

    static void replace(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            // Write and force a sibling temporary file before replacing the visible metadata.
            Path tmp =
                    Files.createTempFile(
                            path.getParent(), path.getFileName().toString() + ".", ".tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                writeFully(ch, ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            replaceFile(tmp, path);
            forceDirectory(path.getParent());
        } catch (IOException e) {
            throw new StorageException("FILE_IO_ERROR", "持久化文件失败: " + e.getMessage(), null, e);
        }
    }

    private static void replaceFile(Path temporary, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException denied) {
                // Windows indexers/antivirus may briefly hold the destination. Retry the
                // same durable temporary image, with a bounded delay; never retry other I/O errors.
                if (attempt == 4) throw denied;
                try {
                    Thread.sleep(25L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("文件替换等待被中断", interrupted);
                }
            }
        }
    }

    static void forceDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            /* Windows does not expose directory fsync. */
        }
    }

    static void writeFully(FileChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) ch.write(b);
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    static byte[] unb64(String s, String code) {
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new StorageException(code, "data 不是合法 Base64", null, e);
        }
    }

    static String compact(Object o) {
        try {
            return JSON.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new StorageException("STORAGE_INTERNAL_ERROR", e.getMessage(), null, e);
        }
    }

    static byte[] compactBytes(Object o) {
        return compact(o).getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, Object> map() {
        return new java.util.LinkedHashMap<>();
    }
}
