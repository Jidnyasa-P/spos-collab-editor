package com.spos.editor;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stress test (no web server). Proves two things:
 *
 *  Part 1 - the lock itself: writers change two plain, unprotected ints one after the other;
 *           readers verify the ints are always equal. Any overlap would be caught.
 *  Part 2 - the Section layer: many writers fight over one section while readers keep reading.
 *           Successful commits must equal the final version number (no lost update).
 *
 * Run:  java -cp out com.spos.editor.StressTest
 */
public class StressTest {

    static int a = 0, b = 0;                           // deliberately NOT volatile or atomic

    public static void main(String[] args) throws Exception {
        boolean ok = partOneLock() & partTwoSection();
        System.out.println(ok ? "\nRESULT: PASS" : "\nRESULT: FAIL");
        System.exit(ok ? 0 : 1);
    }

    static boolean partOneLock() throws Exception {
        final int READERS = 40, READS = 2000, WRITERS = 4, WRITES = 500;
        ReaderPriorityLock lock = new ReaderPriorityLock();
        AtomicInteger readersInside = new AtomicInteger(), writersInside = new AtomicInteger();
        AtomicInteger maxReaders = new AtomicInteger(), violations = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(READERS + WRITERS);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < READERS; i++) pool.submit(() -> {
            try {
                go.await();
                for (int k = 0; k < READS; k++) {
                    lock.readLock();
                    try {
                        int r = readersInside.incrementAndGet();
                        maxReaders.accumulateAndGet(r, Math::max);
                        if (writersInside.get() != 0 || a != b) violations.incrementAndGet();
                        Thread.yield();                // stay inside a moment so readers overlap
                        readersInside.decrementAndGet();
                    } finally { lock.readUnlock(); }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        for (int i = 0; i < WRITERS; i++) pool.submit(() -> {
            try {
                go.await();
                for (int k = 0; k < WRITES; k++) {
                    while (!lock.tryWriteLock(10_000)) { /* keep trying: readers have priority */ }
                    try {
                        if (writersInside.incrementAndGet() != 1 || readersInside.get() != 0) violations.incrementAndGet();
                        a++;
                        Thread.yield();                // invite a race if the lock were broken
                        b++;
                        writersInside.decrementAndGet();
                    } finally { lock.writeUnlock(); }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        long t0 = System.nanoTime();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(120, TimeUnit.SECONDS);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        boolean pass = violations.get() == 0 && a == b && a == WRITERS * WRITES && maxReaders.get() > 1;
        System.out.println("Part 1: lock stress");
        System.out.printf("  %d readers x %d reads, %d writers x %d writes in %d ms%n", READERS, READS, WRITERS, WRITES, ms);
        System.out.printf("  violations = %d, a = %d, b = %d (expected %d), max readers inside together = %d%n",
                violations.get(), a, b, WRITERS * WRITES, maxReaders.get());
        System.out.println("  " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    static boolean partTwoSection() throws Exception {
        final int WRITERS = 5, ATTEMPTS = 300, READERS = 20;
        Section sec = new Section(1, "Stress", "start");
        AtomicInteger committed = new AtomicInteger(), denied = new AtomicInteger(), busy = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < READERS; i++) pool.submit(() -> {
            try { go.await(); for (int k = 0; k < 1000; k++) sec.snapshot(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        for (int i = 0; i < WRITERS; i++) {
            final String user = "writer" + i;
            pool.submit(() -> {
                try {
                    go.await();
                    for (int k = 0; k < ATTEMPTS; k++) {
                        if (!sec.claim(user).granted()) { denied.incrementAndGet(); continue; }
                        Section.Result r = sec.commit(user, user + "-" + k).result();
                        if (r == Section.Result.OK) committed.incrementAndGet();
                        else { busy.incrementAndGet(); sec.release(user); }
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(120, TimeUnit.SECONDS);

        long version = sec.snapshot().version();
        boolean pass = committed.get() == version && committed.get() > 0;
        System.out.println("Part 2: section stress");
        System.out.printf("  committed = %d, final version = %d, claims denied = %d, busy = %d%n",
                committed.get(), version, denied.get(), busy.get());
        System.out.println("  " + (pass ? "PASS (no lost updates)" : "FAIL"));
        return pass;
    }
}
