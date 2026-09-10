package org.chainpage.storage;

import java.util.*;

/** Tracks reentrant page read/write locks by logical owner; conflicting requests do not block. */
public final class LockManager {
    private static final class State {
        final Map<String, Integer> readers = new HashMap<>();
        String writer;
        int count;
    }

    private final Map<Integer, State> states = new HashMap<>();

    public synchronized Map<String, Object> lockPage(int id, String mode, String owner) {
        FileManager.valid(id);
        String normalizedMode = mode == null ? "" : mode.toUpperCase(Locale.ROOT);
        String normalizedOwner = owner == null ? "" : owner.trim();
        if (!normalizedMode.equals("READ") && !normalizedMode.equals("WRITE"))
            throw new StorageException("LOCK_INVALID_MODE", "mode 只支持 READ 或 WRITE", id);
        if (normalizedOwner.isEmpty())
            throw new StorageException("LOCK_INVALID_OWNER", "owner 不能为空", id);
        State s = states.computeIfAbsent(id, k -> new State());
        boolean granted;
        if (normalizedMode.equals("READ")) {
            granted = s.writer == null || s.writer.equals(normalizedOwner);
            if (granted) s.readers.merge(normalizedOwner, 1, Integer::sum);
        } else if (normalizedOwner.equals(s.writer)) {
            s.count++;
            granted = true;
        } else {
            granted =
                    s.writer == null
                            && s.readers.entrySet().stream()
                                    .noneMatch(
                                            e ->
                                                    !e.getKey().equals(normalizedOwner)
                                                            && e.getValue() > 0);
            if (granted) {
                s.writer = normalizedOwner;
                s.count = 1;
            }
        }
        Map<String, Object> r = JsonFiles.map();
        r.put("granted", granted);
        if (!granted) r.put("wait", true);
        return r;
    }

    public synchronized Map<String, Object> unlockPage(int id, String owner) {
        FileManager.valid(id);
        owner = owner == null ? "" : owner.trim();
        if (owner.isEmpty()) throw new StorageException("LOCK_INVALID_OWNER", "owner 不能为空", id);
        State s = states.get(id);
        if (s == null) throw new StorageException("LOCK_NOT_HELD", "该页没有锁", id);
        if (owner.equals(s.writer) && s.count > 0) {
            if (--s.count == 0) s.writer = null;
        } else if (s.readers.getOrDefault(owner, 0) > 0) {
            int n = s.readers.get(owner) - 1;
            if (n == 0) s.readers.remove(owner);
            else s.readers.put(owner, n);
        } else throw new StorageException("LOCK_NOT_OWNER", "解锁者不是锁持有者", id);
        if (s.writer == null && s.readers.isEmpty()) states.remove(id);
        return Map.of("released", true);
    }

    synchronized Lease acquire(int id, String mode, String owner) {
        if (!Boolean.TRUE.equals(lockPage(id, mode, owner).get("granted")))
            throw new StorageException("PAGE_LOCK_BUSY", "页锁冲突", id);
        return new Lease(this, id, owner);
    }

    static final class Lease implements AutoCloseable {
        private final LockManager m;
        private final int id;
        private final String owner;
        private boolean closed;

        Lease(LockManager m, int id, String o) {
            this.m = m;
            this.id = id;
            owner = o;
        }

        public void close() {
            if (!closed) {
                closed = true;
                m.unlockPage(id, owner);
            }
        }
    }
}
