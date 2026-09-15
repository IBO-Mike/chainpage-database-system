package org.chainpage.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 在指定持久化边界模拟进程中止，用于崩溃恢复测试。 */
final class CrashHooks {
    private static final String POINT = System.getenv("CHAINPAGE_CRASH_POINT");
    private static final int TARGET =
            Integer.parseInt(System.getenv().getOrDefault("CHAINPAGE_CRASH_COUNT", "1"));
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTS =
            new ConcurrentHashMap<>();

    static void hit(String point) {
        if (point.equals(POINT)
                && COUNTS.computeIfAbsent(point, x -> new AtomicInteger()).incrementAndGet()
                        == TARGET) Runtime.getRuntime().halt(61);
    }

    private CrashHooks() {}
}
