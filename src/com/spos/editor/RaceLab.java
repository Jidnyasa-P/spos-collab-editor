package com.spos.editor;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Race Lab: the same workload run WITHOUT a lock and WITH the reader-priority lock,
 * slowly enough to watch.
 *
 * Shared data: a counter "a" and a mirror copy "b". The rule (invariant) is that they agree
 * whenever nobody is in the middle of an update.
 *
 *  Writer: read a, think, write a = value+1, pause, write b = value+1.   (a read-modify-write)
 *  Reader: read a, pause, read b, and check they agree.
 *
 * Without a lock two things go wrong:
 *   - LOST UPDATES: two writers read the same value and both write value+1, so one increment vanishes.
 *   - INCONSISTENT READS: a reader looks while a writer is half-way through.
 * With the lock both counts stay at zero.
 */
public final class RaceLab {
    private RaceLab() {}

    public static final long FLOOD_MS = 4000;

    public record Params(int writers, int readers, int ops, int stepMs, boolean busyReaders) {
        public Params {
            writers = Math.max(2, Math.min(6, writers));
            readers = Math.max(1, Math.min(5, readers));
            ops = Math.max(2, Math.min(12, ops));
            stepMs = Math.max(20, Math.min(600, stepMs));
            // keep the slowest (locked) run under about 40 seconds
            int cap = Math.max(20, 40_000 / (writers * ops * 2));
            stepMs = Math.min(stepMs, cap);
        }
        public int expected() { return writers * ops; }
    }

    public record Result(boolean safe, int expected, int finalCounter, int finalMirror, int lost,
                         int reads, int inconsistentReads, int maxReadersTogether, long elapsedMs) {}

    public interface Listener {
        /** state: idle, waiting, reading, writing, done */
        void actor(boolean safe, String who, String state);
        /** kind: info, good, bad */
        void log(boolean safe, String kind, String text);
        void stats(boolean safe, int counter, int opsDone, int expected, int lost, int reads,
                   int inconsistent, int readersInside, int writersInside, int waitingWriters);
    }

    public static Result run(boolean safe, Params p, Listener l, BooleanSupplier cancelled) throws InterruptedException {
        return new Run(safe, p, l, cancelled).execute();
    }

    private static final class Run {
        final boolean safe;
        final Params p;
        final Listener l;
        final BooleanSupplier cancelled;
        final ReaderPriorityLock lock = new ReaderPriorityLock();

        volatile int a = 0, b = 0;
        volatile String lastWriter = "nobody";
        final AtomicInteger opsDone = new AtomicInteger(), lostLive = new AtomicInteger(),
                reads = new AtomicInteger(), inconsistent = new AtomicInteger(),
                readersIn = new AtomicInteger(), writersIn = new AtomicInteger(),
                maxReaders = new AtomicInteger(), writersFinished = new AtomicInteger();
        final java.util.concurrent.ConcurrentHashMap<String, String> states = new java.util.concurrent.ConcurrentHashMap<>();
        final long t0 = System.nanoTime();

        Run(boolean safe, Params p, Listener l, BooleanSupplier cancelled) {
            this.safe = safe; this.p = p; this.l = l; this.cancelled = cancelled;
        }

        long ms() { return (System.nanoTime() - t0) / 1_000_000; }

        void state(String who, String s) {
            if (!s.equals(states.put(who, s))) l.actor(safe, who, s);
        }

        void pause(long ms) throws InterruptedException { Thread.sleep(ms); }

        Result execute() throws InterruptedException {
            Thread[] ts = new Thread[p.writers() + p.readers()];
            int n = 0;
            for (int i = 1; i <= p.writers(); i++) { final int id = i; ts[n++] = daemon(() -> writer(id), "lab-w" + i); }
            for (int i = 1; i <= p.readers(); i++) { final int id = i; ts[n++] = daemon(() -> reader(id), "lab-r" + i); }
            for (Thread t : ts) t.start();

            boolean alive = true;
            while (alive) {
                emitStats();
                alive = false;
                for (Thread t : ts) { t.join(100 / ts.length + 1); if (t.isAlive()) alive = true; }
                if (cancelled.getAsBoolean()) { for (Thread t : ts) t.interrupt(); break; }
            }
            for (Thread t : ts) t.join(500);
            emitStats();
            int lost = Math.max(0, p.expected() - a);
            return new Result(safe, p.expected(), a, b, lost, reads.get(), inconsistent.get(), maxReaders.get(), ms());
        }

        Thread daemon(ThrowingRunnable r, String name) {
            Thread t = new Thread(() -> {
                try { r.run(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, name);
            t.setDaemon(true);
            return t;
        }

        void emitStats() {
            int readersInside = safe ? lock.activeReaders() : readersIn.get();
            int waiting = safe ? lock.waitingWriters() : 0;
            int lost = safe ? 0 : Math.max(0, opsDone.get() - a);
            l.stats(safe, a, opsDone.get(), p.expected(), lost, reads.get(), inconsistent.get(),
                    readersInside, writersIn.get(), waiting);
        }

        void writer(int idx) throws InterruptedException {
            String who = "Writer " + idx;
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            try {
                for (int k = 0; k < p.ops() && !cancelled.getAsBoolean(); k++) {
                    if (safe) {
                        state(who, "waiting");
                        long w0 = ms();
                        boolean readersWereIn = lock.activeReaders() > 0;
                        if (!lock.tryWriteLock(120_000)) return;
                        long waited = ms() - w0;
                        writersIn.incrementAndGet();
                        state(who, "writing");
                        if (waited > 600) {
                            l.log(true, "info", who + " waited " + String.format("%.1f", waited / 1000.0) + " s for the lock ("
                                    + (readersWereIn ? "readers were inside" : "another writer held it") + ").");
                        }
                        int v = a;
                        pause(p.stepMs());
                        a = v + 1;
                        pause(p.stepMs());
                        b = v + 1;
                        opsDone.incrementAndGet();
                        writersIn.decrementAndGet();
                        lock.writeUnlock();
                        l.log(true, "good", who + " saved. Counter is now " + (v + 1) + ".");
                    } else {
                        state(who, "writing");
                        writersIn.incrementAndGet();
                        int v = a;
                        pause(p.stepMs());
                        int now = a;
                        if (now != v) {
                            lostLive.incrementAndGet();
                            l.log(false, "bad", who + " read " + v + ", but " + lastWriter + " has since made it " + now
                                    + ". Writing " + (v + 1) + " erases that work.");
                        }
                        a = v + 1;
                        lastWriter = who;
                        pause(p.stepMs());
                        b = v + 1;
                        opsDone.incrementAndGet();
                        writersIn.decrementAndGet();
                    }
                    state(who, "idle");
                    pause(p.stepMs() / 2 + rnd.nextInt(p.stepMs() + 1));
                }
            } finally {
                state(who, "done");
                writersFinished.incrementAndGet();
            }
        }

        void reader(int idx) throws InterruptedException {
            String who = "Reader " + idx;
            long deadline = p.busyReaders() ? ms() + FLOOD_MS : Long.MAX_VALUE;
            Random rnd = new Random(idx * 31L);
            try {
                pause(rnd.nextInt(p.stepMs() + 1));
                while (writersFinished.get() < p.writers() && ms() < deadline && !cancelled.getAsBoolean()) {
                    int x, y;
                    if (safe) {
                        state(who, "waiting");
                        lock.readLock();
                        maxReaders.accumulateAndGet(lock.activeReaders(), Math::max);
                        state(who, "reading");
                        x = a;
                        pause(p.stepMs() / 3);
                        y = b;
                        pause(p.stepMs() - p.stepMs() / 3);
                        lock.readUnlock();
                    } else {
                        state(who, "reading");
                        readersIn.incrementAndGet();
                        x = a;
                        pause(p.stepMs() / 3);
                        y = b;
                        pause(p.stepMs() - p.stepMs() / 3);
                        readersIn.decrementAndGet();
                    }
                    reads.incrementAndGet();
                    if (x != y) {
                        int c = inconsistent.incrementAndGet();
                        if (c <= 25) l.log(safe, "bad", who + " saw counter " + x + " but mirror " + y + ": an update was half done.");
                    }
                    state(who, "idle");
                    pause(p.busyReaders() ? 5 : p.stepMs());
                }
            } finally {
                state(who, "done");
            }
        }
    }

    private interface ThrowingRunnable { void run() throws InterruptedException; }
}
