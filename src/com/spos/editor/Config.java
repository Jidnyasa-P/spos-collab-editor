package com.spos.editor;

/**
 * Runtime settings. The "hold" values exist only so the reader-priority
 * behaviour can be slowed down and watched during a demo.
 * Volatile fields are used so every thread immediately sees a change.
 */
public final class Config {
    private Config() {}

    public static final long MAX_HOLD_MS = 5000;
    public static final int MAX_TEXT = 5000;

    /** How long every read keeps the section's read lock (demo only, default 0). */
    public static volatile long readHoldMs = clamp(envLong("READ_HOLD_MS", 0));
    /** How long every commit stays inside the critical section (demo only, default 0). */
    public static volatile long writeHoldMs = clamp(envLong("WRITE_HOLD_MS", 0));
    /** How long a commit may wait for readers to leave before answering BUSY. */
    public static volatile long writeWaitMs = envLong("WRITE_WAIT_MS", 5000);
    /** Edit-claim lease; renewed by the browser heartbeat. */
    public static final long LEASE_MS = envLong("LEASE_SECONDS", 60) * 1000L;

    public static long clamp(long v) {
        return Math.max(0, Math.min(MAX_HOLD_MS, v));
    }

    public static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    public static long envLong(String key, long fallback) {
        try {
            return Long.parseLong(env(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
