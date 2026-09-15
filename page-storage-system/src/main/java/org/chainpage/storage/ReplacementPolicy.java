package org.chainpage.storage;

import java.util.*;

/** 独立维护缓存页的淘汰顺序，不保存页面内容。 */
interface ReplacementPolicy {
    void insert(int id);

    void access(int id);

    void remove(int id);

    int victim(Set<Integer> residents);

    String name();

    static ReplacementPolicy make(String name) {
        if ("LRU".equalsIgnoreCase(name)) return new Lru();
        if ("FIFO".equalsIgnoreCase(name)) return new Fifo();
        if ("CLOCK".equalsIgnoreCase(name)) return new Clock();
        throw new StorageException("BUFFER_POLICY_INVALID", "policy 只支持 LRU、FIFO 或 CLOCK");
    }

    abstract class Base implements ReplacementPolicy {
        void validate(Set<Integer> r, Collection<Integer> known) {
            if (r == null || r.isEmpty() || !known.containsAll(r))
                throw new StorageException("BUFFER_POLICY_INVALID_STATE", "候选页集合无效");
        }
    }

    final class Lru extends Base {
        // access-order 模式会在访问后把页面移动到队尾。
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
        // 读取不改变插入顺序，因此队首始终是 FIFO 淘汰页。
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

    /** CLOCK 二次机会算法：环形指针先清引用位，再选择淘汰页。 */
    final class Clock extends Base {
        private static final class Entry {
            final int id;
            boolean referenced = true;
            Entry previous, next;

            Entry(int id) {
                this.id = id;
            }
        }

        private final Map<Integer, Entry> entries = new HashMap<>();
        private Entry hand;

        public void insert(int id) {
            Entry existing = entries.get(id);
            if (existing != null) {
                existing.referenced = true;
                return;
            }
            Entry entry = new Entry(id);
            if (hand == null) {
                entry.next = entry.previous = entry;
                hand = entry;
            } else {
                entry.previous = hand.previous;
                entry.next = hand;
                hand.previous.next = entry;
                hand.previous = entry;
            }
            entries.put(id, entry);
        }

        public void access(int id) {
            Entry entry = entries.get(id);
            if (entry == null)
                throw new StorageException("BUFFER_POLICY_INVALID_STATE", "CLOCK 未登记页", id);
            entry.referenced = true;
        }

        public void remove(int id) {
            Entry entry = entries.remove(id);
            if (entry == null) return;
            if (entries.isEmpty()) {
                hand = null;
                return;
            }
            entry.previous.next = entry.next;
            entry.next.previous = entry.previous;
            if (hand == entry) hand = entry.next;
        }

        public int victim(Set<Integer> residents) {
            validate(residents, entries.keySet());
            // 跳过候选集合外的页面，引用位清零后下一轮即可选出淘汰页。
            while (true) {
                Entry candidate = hand;
                hand = hand.next;
                if (!residents.contains(candidate.id)) continue;
                if (candidate.referenced) candidate.referenced = false;
                else return candidate.id;
            }
        }

        public String name() {
            return "CLOCK";
        }
    }
}
