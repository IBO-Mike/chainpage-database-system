package org.chainpage.storage;

import java.nio.file.Path;
import java.util.Map;

public final class CrashHarness {
    public static void main(String[] args) {
        StorageManager s = new StorageManager(Path.of(args[0]), 4, "LRU", true);
        switch (args[1]) {
            case "insert" -> s.indexInsert(1, 16, Map.of("pageId", 16, "slotId", 0));
            case "delete" -> s.indexDelete(1, 0, null);
            case "drop-table" -> s.dropTablePages("t");
            case "allocate-table" -> s.allocatePageForTable("t");
            case "open" -> {}
            default -> throw new IllegalArgumentException(args[1]);
        }
    }
}
