package org.chainpage.storage;

import java.util.*;

/** Tracks replacement order independently of the page contents held by BufferPool. */
interface ReplacementPolicy {
    void insert(int id);

    void access(int id);

    void remove(int id);

    int victim(Set<Integer> residents);

    String name();

    static ReplacementPolicy make(String name) {
        if ("LRU".equalsIgnoreCase(name)) return new Lru();
        if ("FIFO".equalsIgnoreCase(name)) return new Fifo();
        throw new StorageException("BUFFER_POLICY_INVALID", "policy 只支持 LRU 或 FIFO");
    }

    abstract class Base implements ReplacementPolicy {
        void validate(Set<Integer> r, Collection<Integer> known) {
            if (r == null || r.isEmpty() || !known.containsAll(r))
                throw new StorageException("BUFFER_POLICY_INVALID_STATE", "候选页集合无效");
        }
    }

    final class Lru extends Base {
        // Access-order mode moves a page to the newest position on get/put.
        private final LinkedHashMap<Integer, Boolean> order = new LinkedHashMap<>(16, .75f, true);

        public void insert(int i) {
            order.put(i, true);
        }

        public void access(int i) {
            if (!order.containsKey(i))
                throw new StorageException("BUFFER_POLICY_INVALID_STATE", "LRU 未登记页", i);
            order.get(i);
        }

        public void remove(int i) {
            order.remove(i);
        }

        public int victim(Set<Integer> r) {
            validate(r, order.keySet());
            return order.keySet().stream().filter(r::contains).findFirst().orElseThrow();
        }

        public String name() {
            return "LRU";
        }
    }

    final class Fifo extends Base {
        // Insertion order remains unchanged by reads, giving FIFO its eviction order.
        private final LinkedHashSet<Integer> order = new LinkedHashSet<>();

        public void insert(int i) {
            order.add(i);
        }

        public void access(int i) {
            if (!order.contains(i))
                throw new StorageException("BUFFER_POLICY_INVALID_STATE", "FIFO 未登记页", i);
        }

        public void remove(int i) {
            order.remove(i);
        }

        public int victim(Set<Integer> r) {
            validate(r, order);
            return order.stream().filter(r::contains).findFirst().orElseThrow();
        }

        public String name() {
            return "FIFO";
        }
    }
}
