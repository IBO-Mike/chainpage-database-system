package org.chainpage.storage;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Owns the directory file lock and retires an idle in-process instance when it is reopened. */
final class DirectoryLease implements AutoCloseable {
    private static final Map<Path, DirectoryLease> LIVE = new HashMap<>();
    private final Path key;
    private final FileChannel channel;
    private final FileLock lock;
    private ReentrantLock operationGuard;
    private boolean closed;

    DirectoryLease(Path root) {
        key = root.toAbsolutePath().normalize();
        synchronized (LIVE) {
            DirectoryLease old = LIVE.get(key);
            if (old != null && !old.closed) old.retireForReopen();
            try {
                channel =
                        FileChannel.open(
                                root.resolve("storage.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                lock = channel.tryLock();
                if (lock == null) throw new IOException("locked");
                LIVE.put(key, this);
            } catch (IOException | OverlappingFileLockException e) {
                throw new StorageException("STORAGE_BUSY", "另一个进程已打开该目录", null, e);
            }
        }
    }

    synchronized void attach(ReentrantLock guard) {
        operationGuard = guard;
    }

    private void retireForReopen() {
        ReentrantLock guard;
        synchronized (this) {
            guard = operationGuard;
        }
        if (guard != null && !guard.tryLock())
            throw new StorageException("STORAGE_BUSY", "当前实例仍在执行存储操作");
        try {
            close();
        } finally {
            if (guard != null) guard.unlock();
        }
    }

    synchronized void check() {
        if (closed) throw new StorageException("STORAGE_CLOSED", "存储已关闭或被重新打开");
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            try {
                lock.release();
                channel.close();
            } catch (IOException ignored) {
            }
        }
        synchronized (LIVE) {
            if (LIVE.get(key) == this) LIVE.remove(key);
        }
    }
}
