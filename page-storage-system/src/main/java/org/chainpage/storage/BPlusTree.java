package org.chainpage.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/** 基于页面的 B+ 树，支持局部分裂、借位合并和变长键容量检查。 */
final class BPlusTree {
    private static final int MAGIC = 0x43504231, HEADER = 8, MAX_KEYS = 16, MAX_KEY_BYTES = 1024;

    record Meta(int rootPageId, boolean unique, String keyType) {}

    record Stats(int height, int nodes, int leaves) {}

    private record Node(
            int page,
            boolean leaf,
            List<Object> keys,
            List<List<Map<String, Object>>> values,
            List<Integer> children,
            Integer prev,
            Integer next) {}

    private final Path path;
    private final PageManager pages;
    private final BufferPool buffer;
    private final Map<Integer, Meta> indexes = new TreeMap<>();

    BPlusTree(Path root, PageManager pages, BufferPool buffer) {
        this.path = root.resolve("indexes.json");
        this.pages = pages;
        this.buffer = buffer;
        load();
    }

    synchronized void load() {
        indexes.clear();
        if (!Files.exists(path)) {
            persist();
            return;
        }
        try {
            JsonNode n = JsonFiles.parse(Files.readAllBytes(path), "INDEX_METADATA_CORRUPT");
            if (!n.isObject()) throw metadata("根不是对象");
            n.fields()
                    .forEachRemaining(
                            e -> {
                                try {
                                    int id = Integer.parseInt(e.getKey());
                                    JsonNode m = e.getValue();
                                    JsonNode rootNode = m.get("rootPageId");
                                    if (rootNode == null
                                            || !rootNode.isIntegralNumber()
                                            || !rootNode.canConvertToInt()) throw new Exception();
                                    int root = rootNode.intValue();
                                    if (id < 0
                                            || !pages.isAllocated(root)
                                            || !m.path("unique").isBoolean()) throw new Exception();
                                    String type =
                                            m.get("keyType").isNull()
                                                    ? null
                                                    : m.path("keyType").asText();
                                    if (type != null
                                            && !type.equals("INT")
                                            && !type.equals("VARCHAR")) throw new Exception();
                                    indexes.put(
                                            id,
                                            new Meta(root, m.path("unique").booleanValue(), type));
                                } catch (Exception x) {
                                    throw metadata("索引元数据非法");
                                }
                            });
            Set<Integer> owned = new HashSet<>();
            for (var e : indexes.entrySet()) {
                for (int page : collect(e.getValue().rootPageId))
                    if (!owned.add(page)) throw metadata("索引之间共享物理页");
                validate(e.getKey());
            }
        } catch (java.io.IOException e) {
            throw new StorageException("INDEX_METADATA_CORRUPT", e.getMessage(), null, e);
        }
    }

    synchronized void persist() {
        Map<String, Object> out = new TreeMap<>();
        indexes.forEach(
                (id, m) -> {
                    Map<String, Object> x = JsonFiles.map();
                    x.put("rootPageId", m.rootPageId);
                    x.put("unique", m.unique);
                    x.put("keyType", m.keyType);
                    out.put(id.toString(), x);
                });
        JsonFiles.replace(path, JsonFiles.json(out));
    }

    synchronized Map<String, Object> create(int id, boolean unique, String type) {
        if (id < 0) throw new StorageException("INDEX_INVALID_ID", "indexId 必须非负");
        if (indexes.containsKey(id)) throw new StorageException("INDEX_EXISTS", "索引已存在");
        if (type != null) {
            type = type.toUpperCase(Locale.ROOT);
            if (!type.equals("INT") && !type.equals("VARCHAR"))
                throw new StorageException("INDEX_KEY_TYPE_ERROR", "keyType 非法");
        }
        int root = pages.allocatePage();
        write(root, leaf(List.of(), List.of(), null, null));
        indexes.put(id, new Meta(root, unique, type));
        persist();
        Map<String, Object> result = JsonFiles.map();
        result.put("indexId", id);
        result.put("rootPageId", root);
        result.put("unique", unique);
        result.put("keyType", type);
        return result;
    }

    synchronized Map<String, Object> drop(int id) {
        Meta m = meta(id);
        List<Integer> old = collect(m.rootPageId);
        indexes.remove(id);
        persist();
        for (int p : old) {
            buffer.forceDiscard(p);
            pages.freePage(p);
        }
        return Map.of("indexId", id, "removed", true, "freedPageIds", old);
    }

    synchronized Map<String, Object> insert(int id, Object key, Map<String, Object> rowId) {
        Meta m = meta(id);
        String type = validateKey(key, m.keyType, true);
        Map<String, Object> rid = rowId(rowId);
        Split split = insertNode(m.rootPageId, key, rid, m.unique, type);
        int root = m.rootPageId;
        if (split != null) {
            root = pages.allocatePage();
            write(root, internal(List.of(split.separator), List.of(m.rootPageId, split.right)));
        }
        if (root != m.rootPageId || !Objects.equals(type, m.keyType)) {
            indexes.put(id, new Meta(root, m.unique, type));
            persist();
        }
        return Map.of("inserted", true);
    }

    private record Split(Object separator, int right) {}

    private Split insertNode(
            int page, Object key, Map<String, Object> rid, boolean unique, String type) {
        Node node = read(page);
        List<Object> keys = new ArrayList<>(node.keys);
        if (node.leaf) {
            List<List<Map<String, Object>>> values = new ArrayList<>();
            node.values.forEach(v -> values.add(new ArrayList<>(v)));
            int at = Collections.binarySearch(keys, key, BPlusTree::compare);
            if (at >= 0) {
                if (unique) throw new StorageException("INDEX_DUPLICATE_KEY", "重复索引键");
                if (values.get(at).contains(rid))
                    throw new StorageException("INDEX_DUPLICATE_ROWID", "重复 RowId");
                values.get(at).add(rid);
            } else {
                at = -at - 1;
                keys.add(at, key);
                values.add(at, new ArrayList<>(List.of(rid)));
            }
            Map<String, Object> updated = leaf(keys, values, node.prev, node.next);
            if (keys.size() <= MAX_KEYS && fits(updated)) {
                write(page, updated);
                return null;
            }
            int cut = leafCut(keys, values, type);
            int right = pages.allocatePage();
            write(page, leaf(keys.subList(0, cut), values.subList(0, cut), node.prev, right));
            write(
                    right,
                    leaf(
                            keys.subList(cut, keys.size()),
                            values.subList(cut, values.size()),
                            page,
                            node.next));
            if (node.next != null) {
                Node neighbor = read(node.next);
                write(neighbor.page, leaf(neighbor.keys, neighbor.values, right, neighbor.next));
            }
            return new Split(keys.get(cut), right);
        }
        List<Integer> children = new ArrayList<>(node.children);
        int child = 0;
        while (child < keys.size() && compare(key, keys.get(child)) >= 0) child++;
        Split split = insertNode(children.get(child), key, rid, unique, type);
        if (split == null) return null;
        keys.add(child, split.separator);
        children.add(child + 1, split.right);
        Map<String, Object> updated = internal(keys, children);
        if (keys.size() <= MAX_KEYS && fits(updated)) {
            write(page, updated);
            return null;
        }
        int cut = internalCut(keys, children, type);
        int right = pages.allocatePage();
        write(page, internal(keys.subList(0, cut), children.subList(0, cut + 1)));
        write(
                right,
                internal(
                        keys.subList(cut + 1, keys.size()),
                        children.subList(cut + 1, children.size())));
        // 中间分隔键提升到父节点，不在内部子节点中重复保留。
        return new Split(keys.get(cut), right);
    }

    private static int leafCut(
            List<Object> keys, List<List<Map<String, Object>>> values, String type) {
        int minimum = "INT".equals(type) ? 8 : 1;
        int best = -1;
        for (int cut = minimum; cut <= keys.size() - minimum; cut++) {
            if (fits(
                            leaf(
                                    keys.subList(0, cut),
                                    values.subList(0, cut),
                                    Integer.MAX_VALUE,
                                    Integer.MAX_VALUE))
                    && fits(
                            leaf(
                                    keys.subList(cut, keys.size()),
                                    values.subList(cut, values.size()),
                                    Integer.MAX_VALUE,
                                    Integer.MAX_VALUE))
                    && (best < 0
                            || Math.abs(cut * 2 - keys.size()) < Math.abs(best * 2 - keys.size())))
                best = cut;
        }
        if (best < 0) throw new StorageException("INDEX_PAGE_OVERFLOW", "索引项无法按节点占用要求分页");
        return best;
    }

    private static int internalCut(List<Object> keys, List<Integer> children, String type) {
        int minimum = "INT".equals(type) ? 8 : 1;
        for (int distance = 0; distance < keys.size(); distance++) {
            for (int cut : new int[] {keys.size() / 2 - distance, keys.size() / 2 + distance}) {
                if (cut < minimum || keys.size() - cut - 1 < minimum) continue;
                if (fits(internal(keys.subList(0, cut), children.subList(0, cut + 1)))
                        && fits(
                                internal(
                                        keys.subList(cut + 1, keys.size()),
                                        children.subList(cut + 1, children.size())))) return cut;
            }
        }
        throw new StorageException("INDEX_PAGE_OVERFLOW", "内部节点无法分页");
    }

    synchronized Map<String, Object> delete(int id, Object key, Map<String, Object> rowId) {
        Meta m = meta(id);
        validateKey(key, m.keyType, false);
        if ("INT".equals(m.keyType) && m.unique) {
            deleteNode(m.rootPageId, key, rowId == null ? null : rowId(rowId));
            Node root = read(m.rootPageId);
            if (!root.leaf && root.children.size() == 1) {
                indexes.put(id, new Meta(root.children.get(0), m.unique, m.keyType));
                persist();
                release(root.page);
            }
            return Map.of("deleted", true);
        }
        Map<String, List<Map<String, Object>>> all = entries(m);
        List<Map<String, Object>> values = all.get(token(key));
        if (values == null) throw new StorageException("INDEX_KEY_NOT_FOUND", "索引键不存在");
        if (rowId == null) all.remove(token(key));
        else {
            Map<String, Object> rid = rowId(rowId);
            if (!values.remove(rid))
                throw new StorageException("INDEX_ROWID_NOT_FOUND", "RowId 不存在");
            if (values.isEmpty()) all.remove(token(key));
        }
        rebuild(id, m, all);
        return Map.of("deleted", true);
    }

    private boolean deleteNode(int page, Object key, Map<String, Object> rid) {
        Node node = read(page);
        List<Object> keys = new ArrayList<>(node.keys);
        if (node.leaf) {
            int at = Collections.binarySearch(keys, key, BPlusTree::compare);
            if (at < 0) throw new StorageException("INDEX_KEY_NOT_FOUND", "索引键不存在");
            List<List<Map<String, Object>>> values = new ArrayList<>(node.values);
            if (rid != null && !values.get(at).contains(rid))
                throw new StorageException("INDEX_ROWID_NOT_FOUND", "RowId 不存在");
            keys.remove(at);
            values.remove(at);
            write(page, leaf(keys, values, node.prev, node.next));
            return keys.size() < 8;
        }
        List<Integer> children = new ArrayList<>(node.children);
        int child = 0;
        while (child < keys.size() && compare(key, keys.get(child)) >= 0) child++;
        boolean underfull = deleteNode(children.get(child), key, rid);
        // 借位或合并前，先让分隔键反映各右侧子树的新最小键。
        for (int i = 1; i < children.size(); i++)
            keys.set(i - 1, leftmost(children.get(i)).keys.get(0));
        if (underfull) rebalanceChild(keys, children, child);
        // 即使没有下溢，删除子树最小键后也要重新计算分隔键。
        for (int i = 1; i < children.size(); i++)
            keys.set(i - 1, leftmost(children.get(i)).keys.get(0));
        write(page, internal(keys, children));
        return keys.size() < 8;
    }

    private void rebalanceChild(List<Object> separators, List<Integer> children, int at) {
        Node child = read(children.get(at));
        Node left = at > 0 ? read(children.get(at - 1)) : null;
        Node right = at + 1 < children.size() ? read(children.get(at + 1)) : null;
        if (left != null && left.keys.size() > 8) {
            List<Object> donorKeys = new ArrayList<>(left.keys),
                    receiverKeys = new ArrayList<>(child.keys);
            if (child.leaf) {
                List<List<Map<String, Object>>> donorValues = new ArrayList<>(left.values),
                        receiverValues = new ArrayList<>(child.values);
                receiverKeys.add(0, donorKeys.remove(donorKeys.size() - 1));
                receiverValues.add(0, donorValues.remove(donorValues.size() - 1));
                write(left.page, leaf(donorKeys, donorValues, left.prev, left.next));
                write(child.page, leaf(receiverKeys, receiverValues, child.prev, child.next));
            } else {
                List<Integer> donorChildren = new ArrayList<>(left.children),
                        receiverChildren = new ArrayList<>(child.children);
                receiverKeys.add(0, separators.get(at - 1));
                receiverChildren.add(0, donorChildren.remove(donorChildren.size() - 1));
                separators.set(at - 1, donorKeys.remove(donorKeys.size() - 1));
                write(left.page, internal(donorKeys, donorChildren));
                write(child.page, internal(receiverKeys, receiverChildren));
            }
            return;
        }
        if (right != null && right.keys.size() > 8) {
            List<Object> donorKeys = new ArrayList<>(right.keys),
                    receiverKeys = new ArrayList<>(child.keys);
            if (child.leaf) {
                List<List<Map<String, Object>>> donorValues = new ArrayList<>(right.values),
                        receiverValues = new ArrayList<>(child.values);
                receiverKeys.add(donorKeys.remove(0));
                receiverValues.add(donorValues.remove(0));
                write(right.page, leaf(donorKeys, donorValues, right.prev, right.next));
                write(child.page, leaf(receiverKeys, receiverValues, child.prev, child.next));
            } else {
                List<Integer> donorChildren = new ArrayList<>(right.children),
                        receiverChildren = new ArrayList<>(child.children);
                receiverKeys.add(separators.get(at));
                receiverChildren.add(donorChildren.remove(0));
                separators.set(at, donorKeys.remove(0));
                write(right.page, internal(donorKeys, donorChildren));
                write(child.page, internal(receiverKeys, receiverChildren));
            }
            return;
        }
        // 相邻节点都不能借位时，保留左页并合并释放右页。
        int separator = left != null ? at - 1 : at;
        Node first = left != null ? left : child, second = left != null ? child : right;
        if (second == null) return; // 仅根节点只剩一个子节点时出现。
        List<Object> mergedKeys = new ArrayList<>(first.keys);
        if (child.leaf) {
            mergedKeys.addAll(second.keys);
            List<List<Map<String, Object>>> mergedValues = new ArrayList<>(first.values);
            mergedValues.addAll(second.values);
            write(first.page, leaf(mergedKeys, mergedValues, first.prev, second.next));
            if (second.next != null) {
                Node neighbor = read(second.next);
                write(
                        neighbor.page,
                        leaf(neighbor.keys, neighbor.values, first.page, neighbor.next));
            }
        } else {
            mergedKeys.add(separators.get(separator));
            mergedKeys.addAll(second.keys);
            List<Integer> mergedChildren = new ArrayList<>(first.children);
            mergedChildren.addAll(second.children);
            write(first.page, internal(mergedKeys, mergedChildren));
        }
        separators.remove(separator);
        children.remove(separator + 1);
        release(second.page);
    }

    private void release(int page) {
        buffer.forceDiscard(page);
        pages.freePage(page);
    }

    synchronized List<Map<String, Object>> search(int id, Object key) {
        Meta m = meta(id);
        validateKey(key, m.keyType, false);
        Node n = findLeaf(m.rootPageId, key);
        int at = Collections.binarySearch(n.keys, key, BPlusTree::compare);
        return at < 0 ? List.of() : copy(n.values.get(at));
    }

    synchronized List<Map<String, Object>> range(int id, Object start, Object end) {
        Meta m = meta(id);
        if (start != null) validateKey(start, m.keyType, false);
        if (end != null) validateKey(end, m.keyType, false);
        if (start != null && end != null && compare(start, end) > 0)
            throw new StorageException("INDEX_INVALID_RANGE", "start 不能大于 end");
        Node n = start == null ? leftmost(m.rootPageId) : findLeaf(m.rootPageId, start);
        Set<Integer> seen = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        while (true) {
            if (!seen.add(n.page)) throw corrupt("叶链成环", n.page);
            for (int i = 0; i < n.keys.size(); i++) {
                Object k = n.keys.get(i);
                if (start != null && compare(k, start) < 0) continue;
                if (end != null && compare(k, end) > 0) return out;
                out.addAll(copy(n.values.get(i)));
            }
            if (n.next == null) return out;
            n = read(n.next);
        }
    }

    /** 校验路由、节点占用率、树高、页面归属及双向叶链。 */
    synchronized Stats validate(int id) {
        Meta m = meta(id);
        List<Node> leaves = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Set<Integer> depths = new HashSet<>();
        validateNode(m.rootPageId, true, 0, m.keyType, seen, depths, leaves);
        for (Node leaf : leaves)
            for (List<Map<String, Object>> values : leaf.values) {
                if ((m.unique && values.size() != 1)
                        || new HashSet<>(values).size() != values.size())
                    throw corrupt("唯一约束或 RowId 去重约束损坏", leaf.page);
            }
        if (depths.size() != 1) throw corrupt("叶深度不一致", null);
        for (int i = 0; i < leaves.size(); i++) {
            Node n = leaves.get(i);
            Integer prev = i == 0 ? null : leaves.get(i - 1).page,
                    next = i + 1 == leaves.size() ? null : leaves.get(i + 1).page;
            if (!Objects.equals(prev, n.prev) || !Objects.equals(next, n.next))
                throw corrupt("叶链不一致", n.page);
        }
        return new Stats(depths.iterator().next() + 1, seen.size(), leaves.size());
    }

    private List<Object> validateNode(
            int page,
            boolean root,
            int depth,
            String type,
            Set<Integer> seen,
            Set<Integer> depths,
            List<Node> leaves) {
        if (!seen.add(page) || !pages.isAllocated(page)) throw corrupt("重复或未分配子页", page);
        Node n = read(page);
        if (n.keys.size() > MAX_KEYS) throw corrupt("节点过满", page);
        int min = n.leaf ? 8 : 8;
        if (!root && "INT".equals(type) && n.keys.size() < min) throw corrupt("定长节点未半满", page);
        if (!root && n.keys.isEmpty()) throw corrupt("空非根节点", page);
        if (type == null && !n.keys.isEmpty()) throw corrupt("非空索引缺少键类型", page);
        try {
            for (Object key : n.keys) validateKey(key, type, false);
        } catch (StorageException e) {
            throw corrupt("节点键类型错误", page);
        }
        if (n.leaf) {
            for (List<Map<String, Object>> values : n.values) {
                if (values.isEmpty()) throw corrupt("叶节点含空 RowId 列表", page);
                try {
                    for (Map<String, Object> value : values) rowId(value);
                } catch (StorageException e) {
                    throw corrupt("叶节点 RowId 非法", page);
                }
            }
            depths.add(depth);
            leaves.add(n);
            return n.keys;
        }
        if (root && n.keys.isEmpty()) throw corrupt("空内部根", page);
        List<List<Object>> groups = new ArrayList<>();
        for (int c : n.children)
            groups.add(validateNode(c, false, depth + 1, type, seen, depths, leaves));
        for (int i = 0; i < n.keys.size(); i++)
            if (groups.get(i).isEmpty()
                    || groups.get(i + 1).isEmpty()
                    || compare(groups.get(i).get(groups.get(i).size() - 1), n.keys.get(i)) >= 0
                    || compare(n.keys.get(i), groups.get(i + 1).get(0)) > 0)
                throw corrupt("分隔键范围错误", page);
        List<Object> all = new ArrayList<>();
        groups.forEach(all::addAll);
        return all;
    }

    // 重建路径先生成叶层，再逐层生成父节点，不复用增量分裂逻辑。
    private void rebuild(int id, Meta oldMeta, Map<String, List<Map<String, Object>>> all) {
        List<Integer> old = collect(oldMeta.rootPageId);
        List<Object> keys = all.keySet().stream().map(BPlusTree::untoken).toList();
        List<List<Map<String, Object>>> values =
                all.values().stream().map(BPlusTree::copy).toList();
        List<List<Object>> keyGroups = new ArrayList<>();
        List<List<List<Map<String, Object>>>> valueGroups = new ArrayList<>();
        if ("INT".equals(oldMeta.keyType)) {
            for (int[] span : balanced(keys.size(), MAX_KEYS)) {
                keyGroups.add(new ArrayList<>(keys.subList(span[0], span[1])));
                valueGroups.add(new ArrayList<>(values.subList(span[0], span[1])));
            }
        } else {
            int at = 0;
            while (at < keys.size()) {
                int end = Math.min(at + MAX_KEYS, keys.size());
                while (end > at
                        && !fits(
                                leaf(
                                        keys.subList(at, end),
                                        values.subList(at, end),
                                        Integer.MAX_VALUE,
                                        Integer.MAX_VALUE))) end--;
                if (end == at) throw new StorageException("INDEX_PAGE_OVERFLOW", "单个索引项无法放入页");
                keyGroups.add(new ArrayList<>(keys.subList(at, end)));
                valueGroups.add(new ArrayList<>(values.subList(at, end)));
                at = end;
            }
        }
        if (keyGroups.isEmpty()) {
            keyGroups.add(new ArrayList<>());
            valueGroups.add(new ArrayList<>());
        }
        List<Integer> level = new ArrayList<>();
        List<Object> first = new ArrayList<>();
        for (List<Object> group : keyGroups) {
            level.add(pages.allocatePage());
            first.add(group.isEmpty() ? null : group.get(0));
        }
        for (int i = 0; i < level.size(); i++)
            write(
                    level.get(i),
                    leaf(
                            keyGroups.get(i),
                            valueGroups.get(i),
                            i == 0 ? null : level.get(i - 1),
                            i + 1 == level.size() ? null : level.get(i + 1)));
        while (level.size() > 1) {
            List<Integer> next = new ArrayList<>();
            List<Object> nextFirst = new ArrayList<>();
            List<int[]> spans =
                    "INT".equals(oldMeta.keyType)
                            ? balanced(level.size(), MAX_KEYS + 1)
                            : variableInternal(first, level);
            for (int[] span : spans) {
                int p = pages.allocatePage();
                write(
                        p,
                        internal(
                                first.subList(span[0] + 1, span[1]),
                                level.subList(span[0], span[1])));
                next.add(p);
                nextFirst.add(first.get(span[0]));
            }
            level = next;
            first = nextFirst;
        }
        // 先持久化新根，再丢弃并释放旧树页面。
        indexes.put(id, new Meta(level.get(0), oldMeta.unique, oldMeta.keyType));
        persist();
        for (int p : old) {
            buffer.forceDiscard(p);
            pages.freePage(p);
        }
        validate(id);
    }

    // 均匀分组，避免最后一个节点过于稀疏。
    private static List<int[]> balanced(int count, int max) {
        if (count == 0) return List.of();
        int groups = (count + max - 1) / max, base = count / groups, extra = count % groups, at = 0;
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < groups; i++) {
            int size = base + (i < extra ? 1 : 0);
            out.add(new int[] {at, at + size});
            at += size;
        }
        return out;
    }

    private static List<int[]> variableInternal(List<Object> first, List<Integer> level) {
        List<int[]> out = new ArrayList<>();
        int at = 0;
        while (at < level.size()) {
            int end = Math.min(at + MAX_KEYS + 1, level.size());
            while (end > at + 1
                    && !fits(internal(first.subList(at + 1, end), level.subList(at, end)))) end--;
            if (end <= at + 1) throw new StorageException("INDEX_PAGE_OVERFLOW", "内部节点无法放入页");
            out.add(new int[] {at, end});
            at = end;
        }
        if (out.size() > 1 && out.get(out.size() - 1)[1] - out.get(out.size() - 1)[0] < 2) {
            int[] last = out.remove(out.size() - 1), previous = out.get(out.size() - 1);
            previous[1]--;
            out.add(new int[] {previous[1], last[1]});
        }
        return out;
    }

    private Map<String, List<Map<String, Object>>> entries(Meta m) {
        Map<String, List<Map<String, Object>>> out =
                new TreeMap<>((a, b) -> compare(untoken(a), untoken(b)));
        Node n = leftmost(m.rootPageId);
        Set<Integer> seen = new HashSet<>();
        while (true) {
            if (!seen.add(n.page)) throw corrupt("叶链成环", n.page);
            for (int i = 0; i < n.keys.size(); i++)
                out.put(token(n.keys.get(i)), new ArrayList<>(copy(n.values.get(i))));
            if (n.next == null) return out;
            n = read(n.next);
        }
    }

    // 分隔键属于右侧子树，相等键也应向右查找。
    private Node findLeaf(int page, Object key) {
        Set<Integer> seen = new HashSet<>();
        while (true) {
            if (!seen.add(page)) throw corrupt("子页成环", page);
            Node n = read(page);
            if (n.leaf) return n;
            int at = 0;
            while (at < n.keys.size() && compare(key, n.keys.get(at)) >= 0) at++;
            page = n.children.get(at);
        }
    }

    private Node leftmost(int page) {
        Set<Integer> seen = new HashSet<>();
        while (true) {
            if (!seen.add(page)) throw corrupt("子页成环", page);
            Node n = read(page);
            if (n.leaf) return n;
            page = n.children.get(0);
        }
    }

    private List<Integer> collect(int root) {
        List<Integer> out = new ArrayList<>(), stack = new ArrayList<>(List.of(root));
        Set<Integer> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            int p = stack.remove(stack.size() - 1);
            if (!seen.add(p)) throw corrupt("重复子页", p);
            Node n = read(p);
            out.add(p);
            if (!n.leaf) stack.addAll(n.children);
        }
        Collections.sort(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Node read(int id) {
        byte[] raw = buffer.getPage(id, "bptree-" + Thread.currentThread().getId()).data();
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC) throw corrupt("magic 错误", id);
        int length = b.getInt();
        if (length <= 0 || length > 4096 - HEADER) throw corrupt("长度错误", id);
        try {
            Map<String, Object> m =
                    JsonFiles.JSON.readValue(raw, HEADER, length, new TypeReference<>() {});
            if (!(m.get("leaf") instanceof Boolean leaf) || !(m.get("keys") instanceof List<?>))
                throw new Exception();
            List<Object> keys = (List<Object>) m.get("keys");
            for (int i = 1; i < keys.size(); i++)
                if (compare(keys.get(i - 1), keys.get(i)) >= 0) throw new Exception();
            List<List<Map<String, Object>>> values =
                    leaf ? (List<List<Map<String, Object>>>) m.get("values") : List.of();
            List<Integer> children = new ArrayList<>();
            if (!leaf) {
                if (!(m.get("children") instanceof List<?> rawChildren)) throw new Exception();
                for (Object child : rawChildren) {
                    if (!(child instanceof Integer value) || value < 0) throw new Exception();
                    children.add(value);
                }
            }
            if (leaf && values.size() != keys.size() || !leaf && children.size() != keys.size() + 1)
                throw new Exception();
            return new Node(
                    id,
                    leaf,
                    keys,
                    values,
                    children,
                    (Integer) m.get("prev"),
                    (Integer) m.get("next"));
        } catch (Exception e) {
            throw new StorageException("INDEX_PAGE_CORRUPT", "索引页结构错误", id, e);
        }
    }

    private void write(int id, Map<String, Object> node) {
        byte[] json = JsonFiles.compactBytes(node);
        if (json.length + HEADER > 4096)
            throw new StorageException("INDEX_PAGE_OVERFLOW", "索引节点超过 4096 字节");
        ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        b.putInt(MAGIC).putInt(json.length).put(json);
        buffer.putPage(id, b.array(), true, "bptree-" + Thread.currentThread().getId(), 0);
        CrashHooks.hit("index_page_write");
    }

    private static Map<String, Object> leaf(
            List<Object> keys, List<List<Map<String, Object>>> values, Integer prev, Integer next) {
        Map<String, Object> m = JsonFiles.map();
        m.put("leaf", true);
        m.put("keys", keys);
        m.put("values", values);
        m.put("prev", prev);
        m.put("next", next);
        return m;
    }

    private static Map<String, Object> internal(List<Object> keys, List<Integer> children) {
        Map<String, Object> m = JsonFiles.map();
        m.put("leaf", false);
        m.put("keys", keys);
        m.put("children", children);
        return m;
    }

    private static boolean fits(Object value) {
        return JsonFiles.compactBytes(value).length + HEADER <= 4096;
    }

    private Meta meta(int id) {
        Meta m = indexes.get(id);
        if (m == null) throw new StorageException("INDEX_NOT_FOUND", "索引不存在");
        return m;
    }

    synchronized boolean owns(int page) {
        for (Meta m : indexes.values()) if (collect(m.rootPageId).contains(page)) return true;
        return false;
    }

    synchronized void validateDisjoint(TablePageMap tables) {
        for (Meta m : indexes.values())
            for (int page : collect(m.rootPageId))
                if (tables.owns(page)) throw metadata("表与索引共享物理页");
    }

    private static StorageException corrupt(String message, Integer page) {
        return new StorageException("INDEX_PAGE_CORRUPT", message, page);
    }

    private static StorageException metadata(String message) {
        return new StorageException("INDEX_METADATA_CORRUPT", message);
    }

    private static String validateKey(Object key, String expected, boolean infer) {
        String actual =
                key instanceof Integer || key instanceof Long
                        ? "INT"
                        : key instanceof String ? "VARCHAR" : null;
        if (actual == null)
            throw new StorageException("INDEX_KEY_TYPE_ERROR", "索引键只支持 INT 或 VARCHAR");
        if (JsonFiles.compactBytes(key).length > MAX_KEY_BYTES)
            throw new StorageException("INDEX_KEY_TOO_LARGE", "索引键超过 1024 字节");
        if (expected != null && !expected.equals(actual))
            throw new StorageException("INDEX_KEY_TYPE_ERROR", "索引键类型错误");
        return expected == null && infer ? actual : expected;
    }

    private static Map<String, Object> rowId(Map<String, Object> r) {
        if (r == null
                || r.size() != 2
                || !(r.get("pageId") instanceof Integer p)
                || !(r.get("slotId") instanceof Integer s)
                || p < 0
                || s < 0) throw new StorageException("INDEX_INVALID_ROWID", "RowId 非法");
        return Map.of("pageId", p, "slotId", s);
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y)
            return Long.compare(x.longValue(), y.longValue());
        return ((String) a).compareTo((String) b);
    }

    private static String token(Object key) {
        return (key instanceof Number ? "I:" : "S:") + key;
    }

    private static Object untoken(String token) {
        return token.startsWith("I:") ? Long.parseLong(token.substring(2)) : token.substring(2);
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> values) {
        return values.stream().map(LinkedHashMap::new).map(x -> (Map<String, Object>) x).toList();
    }

    Path path() {
        return path;
    }
}
