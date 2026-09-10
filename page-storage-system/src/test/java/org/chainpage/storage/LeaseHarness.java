package org.chainpage.storage;

import java.nio.file.Path;

public final class LeaseHarness {
    public static void main(String[] args) throws Exception {
        try (StorageManager ignored = new StorageManager(Path.of(args[0]))) {
            System.out.println("READY");
            System.out.flush();
            Thread.sleep(30_000);
        }
    }
}
