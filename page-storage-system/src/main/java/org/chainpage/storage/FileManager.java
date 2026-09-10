package org.chainpage.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Performs fixed-size page I/O in pages.dat; page allocation is managed by PageManager. */
public final class FileManager {
    public static final int PAGE_SIZE = 4096;
    private final Path path;

    FileManager(Path path) {
        this.path = path;
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) Files.createFile(path);
        } catch (IOException e) {
            throw io(e, null);
        }
    }

    Path path() {
        return path;
    }

    synchronized long pageCount() {
        try {
            return Files.size(path) / PAGE_SIZE;
        } catch (IOException e) {
            throw io(e, null);
        }
    }

    synchronized byte[] readAt(int id) {
        valid(id);
        byte[] out = new byte[PAGE_SIZE];
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ch.position((long) id * PAGE_SIZE);
            ByteBuffer b = ByteBuffer.wrap(out);
            while (b.hasRemaining() && ch.read(b) >= 0) {}
            if (b.hasRemaining())
                throw new StorageException("FILE_IO_ERROR", "数据文件中的页长度不足 4096 字节", id);
            return out;
        } catch (IOException e) {
            throw io(e, id);
        }
    }

    synchronized void writeAt(int id, byte[] data) {
        valid(id);
        page(data, id);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.position((long) id * PAGE_SIZE);
            JsonFiles.writeFully(ch, ByteBuffer.wrap(data));
            ch.force(true);
        } catch (IOException e) {
            throw io(e, id);
        }
    }

    synchronized int appendZero() {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            long size = ch.size();
            if (size % PAGE_SIZE != 0)
                throw new StorageException("FILE_IO_ERROR", "数据文件长度不是页大小整数倍");
            int id = Math.toIntExact(size / PAGE_SIZE);
            ch.position(size);
            JsonFiles.writeFully(ch, ByteBuffer.wrap(new byte[PAGE_SIZE]));
            ch.force(true);
            return id;
        } catch (IOException | ArithmeticException e) {
            throw io(e, null);
        }
    }

    synchronized void truncate(long bytes) {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.truncate(bytes);
            ch.force(true);
        } catch (IOException e) {
            throw io(e, null);
        }
    }

    synchronized void sync() {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.force(true);
        } catch (IOException e) {
            throw io(e, null);
        }
    }

    static void valid(int id) {
        if (id < 0) throw new StorageException("INVALID_PAGE_ID", "pageId 必须是非负整数", id);
    }

    static void page(byte[] data, Integer id) {
        if (data == null || data.length != PAGE_SIZE)
            throw new StorageException("INVALID_PAGE_SIZE", "页必须恰好为 4096 字节", id);
    }

    private static StorageException io(Exception e, Integer id) {
        return new StorageException("FILE_IO_ERROR", e.getMessage(), id, e);
    }
}
