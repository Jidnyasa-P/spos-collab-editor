package com.spos.editor;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The classic first Readers-Writers solution (reader priority),
 * after Courtois, Heymans and Parnas (1971).
 *
 *   mutex     - binary semaphore that protects readCount
 *   wrt       - binary semaphore: one writer OR the whole group of readers
 *   readCount - number of readers currently inside
 *
 * Only the FIRST reader waits on wrt and only the LAST reader signals it.
 * Every reader in between walks straight in, so readers never block each other
 * and they overtake a writer that is merely waiting. That is "reader priority".
 *
 * Semaphores (not ReentrantLock) are used on purpose: wrt is acquired by
 * whichever thread happens to be the first reader and released by whichever
 * thread is the last one. A semaphore has no owner, so that is legal.
 */
public class ReaderPriorityLock {
    private final Semaphore mutex = new Semaphore(1, true);
    private final Semaphore wrt = new Semaphore(1, true);

    private volatile int readCount = 0;          // modified only while holding mutex
    private final AtomicInteger waitingWriters = new AtomicInteger();
    private volatile boolean writerActive = false;

    /** Entry section of a reader. */
    public void readLock() throws InterruptedException {
        mutex.acquire();
        try {
            if (readCount == 0) {
                wrt.acquire();                   // first reader locks writers out
            }
            readCount = readCount + 1;
        } finally {
            mutex.release();
        }
    }

    /** Exit section of a reader. Never throws, so cleanup always completes. */
    public void readUnlock() {
        mutex.acquireUninterruptibly();
        try {
            readCount = readCount - 1;
            if (readCount == 0) {
                wrt.release();                   // last reader lets writers in
            }
        } finally {
            mutex.release();
        }
    }

    /**
     * Entry section of a writer, with a timeout so a request can fail cleanly
     * instead of waiting forever behind a stream of readers.
     */
    public boolean tryWriteLock(long timeoutMs) throws InterruptedException {
        waitingWriters.incrementAndGet();
        try {
            if (!wrt.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                return false;
            }
            writerActive = true;
            return true;
        } finally {
            waitingWriters.decrementAndGet();
        }
    }

    /** Exit section of a writer. */
    public void writeUnlock() {
        writerActive = false;
        wrt.release();
    }

    // ---- read-only probes used by the UI (they never block) ----
    public int activeReaders()   { return readCount; }
    public int waitingWriters()  { return waitingWriters.get(); }
    public boolean isWriterActive() { return writerActive; }
}
