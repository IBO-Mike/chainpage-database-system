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

final class JsonFiles {
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private JsonFiles() {}
    static byte[] json(Object value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (JsonProcessingException e) { throw new StorageException("STORAGE_INTERNAL_ERROR", e.getMessage(), null, e); }
    }
    static JsonNode parse(byte[] value, String code) {
        try { return JSON.readTree(value); }
        catch (IOException e) { throw new StorageException(code, e.getMessage(), null, e); }
    }
    static void replace(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), path.getFileName().toString()+".", ".tmp");
            try (FileChannel ch=FileChannel.open(tmp, StandardOpenOption.WRITE)) { writeFully(ch, ByteBuffer.wrap(bytes)); ch.force(true); }
            try { Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING); }
            forceDirectory(path.getParent());
        } catch (IOException e) { throw new StorageException("FILE_IO_ERROR", "持久化文件失败: "+e.getMessage(), null, e); }
    }
    static void forceDirectory(Path dir) {
        try (FileChannel ch=FileChannel.open(dir,StandardOpenOption.READ)) { ch.force(true); }
        catch (IOException | UnsupportedOperationException ignored) { /* Windows does not expose directory fsync. */ }
    }
    static void writeFully(FileChannel ch, ByteBuffer b) throws IOException { while (b.hasRemaining()) ch.write(b); }
    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    static byte[] unb64(String s, String code) {
        try { return Base64.getDecoder().decode(s); }
        catch (IllegalArgumentException e) { throw new StorageException(code,"data 不是合法 Base64",null,e); }
    }
    static String compact(Object o) {
        try { return JSON.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new StorageException("STORAGE_INTERNAL_ERROR",e.getMessage(),null,e); }
    }
    static byte[] compactBytes(Object o) { return compact(o).getBytes(StandardCharsets.UTF_8); }
    static Map<String,Object> map() { return new java.util.LinkedHashMap<>(); }
}
