package org.chainpage.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Encodes records in a page with a slot directory, allowing record bytes to move without
 * renumbering live slots.
 */
final class SlottedPage {
    static final int HEADER = 10, SLOT = 8;
    private static final int MAGIC = 0x43505331;

    private static final class Entry {
        byte[] data;
        boolean deleted;

        Entry(byte[] d, boolean x) {
            data = d;
            deleted = x;
        }
    }

    private final List<Entry> slots = new ArrayList<>();

    static SlottedPage from(byte[] raw) {
        FileManager.page(raw, null);
        SlottedPage p = new SlottedPage();
        boolean zero = true;
        for (byte b : raw)
            if (b != 0) {
                zero = false;
                break;
            }
        if (zero) return p;
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC) throw bad("不是 Slotted Page");
        int count = Short.toUnsignedInt(b.getShort()),
                start = Short.toUnsignedInt(b.getShort()),
                end = Short.toUnsignedInt(b.getShort());
        if (start != HEADER + count * SLOT || start > end || end > 4096) throw bad("页头损坏");
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int off = Short.toUnsignedInt(b.getShort()),
                    len = Short.toUnsignedInt(b.getShort()),
                    flags = Byte.toUnsignedInt(b.get());
            b.position(b.position() + 3);
            if (flags == 1) {
                if (off != 0 || len != 0) throw bad("删除槽含指针");
                p.slots.add(new Entry(new byte[0], true));
            } else {
                if (flags != 0 || len <= 0 || off < end || off + len > 4096) throw bad("槽越界");
                ranges.add(new int[] {off, off + len});
                p.slots.add(new Entry(Arrays.copyOfRange(raw, off, off + len), false));
            }
        }
        ranges.sort(Comparator.comparingInt(a -> a[0]));
        for (int i = 1; i < ranges.size(); i++)
            if (ranges.get(i - 1)[1] > ranges.get(i)[0]) throw bad("槽区间重叠");
        return p;
    }

    Insert insert(Map<String, Object> row) {
        if (row == null) throw new StorageException("ROW_INVALID", "Row 必须是对象");
        for (var e : row.entrySet())
            if (e.getKey() == null
                    || !(e.getValue() instanceof String
                            || e.getValue() instanceof Integer
                            || e.getValue() instanceof Long))
                throw new StorageException("ROW_INVALID", "Row 只支持字符串字段名及 INT/VARCHAR 值");
        byte[] data = JsonFiles.compactBytes(row);
        // Reuse a deleted slot before growing the directory, which costs SLOT extra bytes.
        int id = -1;
        for (int i = 0; i < slots.size(); i++)
            if (slots.get(i).deleted) {
                id = i;
                break;
            }
        int added = id < 0 ? SLOT : 0;
        if (freeBytes() < data.length + added)
            throw new StorageException("PAGE_NO_SPACE", "页内剩余空间不足");
        if (id < 0) {
            id = slots.size();
            slots.add(new Entry(data, false));
        } else slots.set(id, new Entry(data, false));
        return new Insert(id, freeBytes());
    }

    Map<String, Object> read(int id) {
        Entry e = require(id);
        if (e.deleted) throw new StorageException("PAGE_SLOT_DELETED", "槽已删除");
        try {
            return JsonFiles.JSON.readValue(
                    e.data, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception x) {
            throw new StorageException("PAGE_RECORD_CORRUPT", x.getMessage());
        }
    }

    void delete(int id) {
        Entry e = require(id);
        if (e.deleted) throw new StorageException("PAGE_SLOT_DELETED", "槽已删除");
        slots.set(id, new Entry(new byte[0], true));
    }

    List<Record> records() {
        List<Record> r = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++)
            if (!slots.get(i).deleted) r.add(new Record(i, read(i)));
        return r;
    }

    int freeBytes() {
        int used = HEADER + slots.size() * SLOT;
        for (Entry e : slots) if (!e.deleted) used += e.data.length;
        return 4096 - used;
    }

    byte[] bytes() {
        if (freeBytes() < 0) throw new StorageException("PAGE_NO_SPACE", "记录超过页容量");
        byte[] raw = new byte[4096];
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        // The directory grows forward from the header; compacted record data grows backward.
        int cursor = 4096;
        List<int[]> layout = new ArrayList<>();
        for (Entry e : slots) {
            if (e.deleted) layout.add(new int[] {0, 0, 1});
            else {
                cursor -= e.data.length;
                System.arraycopy(e.data, 0, raw, cursor, e.data.length);
                layout.add(new int[] {cursor, e.data.length, 0});
            }
        }
        b.putInt(MAGIC)
                .putShort((short) slots.size())
                .putShort((short) (HEADER + slots.size() * SLOT))
                .putShort((short) cursor);
        for (int[] x : layout)
            b.putShort((short) x[0]).putShort((short) x[1]).put((byte) x[2]).put(new byte[3]);
        return raw;
    }

    private Entry require(int id) {
        if (id < 0 || id >= slots.size())
            throw new StorageException("PAGE_SLOT_NOT_FOUND", "slotId 不存在");
        return slots.get(id);
    }

    private static StorageException bad(String m) {
        return new StorageException("PAGE_FORMAT_ERROR", m);
    }

    record Insert(int slotId, int freeBytes) {}

    record Record(int slotId, Map<String, Object> row) {}
}
