package com.spos.editor;

/**
 * Console demonstration of reader priority (no web server needed).
 *
 * Expected story:
 *   - Reader-1 enters. Writer-1 arrives and has to wait.
 *   - Reader-2 and Reader-3 arrive LATER than Writer-1 but still walk straight in.
 *   - Writer-1 gets in only after the last reader has left.
 *   - Reader-4 arrives while Writer-1 is writing and has to wait.
 */
public class LockDemo {
    private static final ReaderPriorityLock LOCK = new ReaderPriorityLock();
    private static long t0;

    private static synchronized void say(String msg) {
        System.out.printf("[%5.2fs] %s%n", (System.nanoTime() - t0) / 1e9, msg);
    }

    private static void reader(String name, long startAfterMs, long holdMs) {
        new Thread(() -> {
            try {
                Thread.sleep(startAfterMs);
                say(name + " arrives");
                LOCK.readLock();
                say(name + " ENTERS reading   (readers inside: " + LOCK.activeReaders() + ")");
                Thread.sleep(holdMs);
                say(name + " leaves");
                LOCK.readUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, name).start();
    }

    private static void writer(String name, long startAfterMs, long holdMs) {
        new Thread(() -> {
            try {
                Thread.sleep(startAfterMs);
                say(name + " arrives and WAITS (needs the section alone)");
                if (!LOCK.tryWriteLock(10_000)) { say(name + " gave up"); return; }
                say(name + " ENTERS writing");
                Thread.sleep(holdMs);
                say(name + " finished writing");
                LOCK.writeUnlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, name).start();
    }

    public static void main(String[] args) {
        System.out.println("Reader-priority demo (times in seconds)\n");
        t0 = System.nanoTime();
        reader("Reader-1", 0, 2000);
        writer("Writer-1", 400, 500);
        reader("Reader-2", 800, 1500);
        reader("Reader-3", 1200, 1000);
        reader("Reader-4", 2500, 300);
    }
}
