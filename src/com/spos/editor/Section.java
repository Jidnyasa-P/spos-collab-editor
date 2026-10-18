package com.spos.editor;

/**
 * One section of the shared document.
 *
 * Two layers of protection:
 *  1. Edit claim (ownership + lease): at most one user may edit a section.
 *     It is cheap, non-blocking and expires, so a closed tab cannot lock a
 *     section forever.
 *  2. ReaderPriorityLock: the moment the text is really replaced happens
 *     inside the write lock, so no reader ever sees a half-finished change.
 */
public class Section {
    public final int id;
    public final String title;
    public final ReaderPriorityLock rw = new ReaderPriorityLock();

    private final long leaseMs;
    private volatile String text;
    private volatile long version;

    // guarded by "this"
    private String editor = null;
    private long leaseUntil = 0;

    /** Content of a section as seen by a reader. */
    public record Snap(int id, String title, String text, long version) {}

    /** Non-blocking view of the lock state (for the UI). */
    public record Status(int id, String editor, long leaseLeftMs, int readers,
                         int waitingWriters, boolean writerActive, long version) {}

    public enum Result { OK, FORBIDDEN, BUSY }

    public record Commit(Result result, long waitedMs, long version) {}

    public record Claim(boolean granted, String holder, boolean alreadyMine) {}

    public Section(int id, String title, String text, long version, long leaseMs) {
        this.id = id;
        this.title = title;
        this.text = text;
        this.version = version;
        this.leaseMs = leaseMs;
    }

    public Section(int id, String title, String text) {
        this(id, title, text, 0, Config.LEASE_MS);
    }

    // ------------------------------------------------------------ READ path
    public Snap snapshot() throws InterruptedException {
        rw.readLock();
        try {
            long hold = Config.readHoldMs;
            if (hold > 0) Thread.sleep(hold);    // demo only: stay inside longer
            return new Snap(id, title, text, version);
        } finally {
            rw.readUnlock();
        }
    }

    /** Non-blocking: never touches the read lock, so it works even during a write. */
    public Status status() {
        String ed;
        long left;
        synchronized (this) {
            ed = validEditor();
            left = (ed == null) ? 0 : Math.max(0, leaseUntil - System.currentTimeMillis());
        }
        return new Status(id, ed, left, rw.activeReaders(), rw.waitingWriters(),
                rw.isWriterActive(), version);
    }

    // ------------------------------------------------------- edit ownership
    /** Must be called while holding the monitor. Expires stale claims. */
    private String validEditor() {
        if (editor != null && System.currentTimeMillis() >= leaseUntil) {
            editor = null;
            leaseUntil = 0;
        }
        return editor;
    }

    public synchronized String currentEditor() {
        return validEditor();
    }

    public synchronized Claim claim(String user) {
        String cur = validEditor();
        if (cur == null || cur.equals(user)) {
            editor = user;
            leaseUntil = System.currentTimeMillis() + leaseMs;
            return new Claim(true, user, cur != null);
        }
        return new Claim(false, cur, false);
    }

    public synchronized boolean renew(String user) {
        if (user.equals(validEditor())) {
            leaseUntil = System.currentTimeMillis() + leaseMs;
            return true;
        }
        return false;
    }

    public synchronized boolean release(String user) {
        if (user.equals(validEditor())) {
            editor = null;
            leaseUntil = 0;
            return true;
        }
        return false;
    }

    // ----------------------------------------------------------- WRITE path
    public Commit commit(String user, String newText) throws InterruptedException {
        if (!user.equals(currentEditor())) {
            return new Commit(Result.FORBIDDEN, 0, version);
        }
        long t0 = System.nanoTime();
        if (!rw.tryWriteLock(Config.writeWaitMs)) {
            return new Commit(Result.BUSY, (System.nanoTime() - t0) / 1_000_000, version);
        }
        long waited = (System.nanoTime() - t0) / 1_000_000;
        try {
            // The lease may have lapsed while we waited for readers to leave.
            if (!user.equals(currentEditor())) {
                return new Commit(Result.FORBIDDEN, waited, version);
            }
            long hold = Config.writeHoldMs;
            if (hold > 0) Thread.sleep(hold);    // demo only
            // ------------------- critical section -------------------
            text = newText;
            version = version + 1;
            // --------------------------------------------------------
        } finally {
            rw.writeUnlock();
        }
        release(user);
        return new Commit(Result.OK, waited, version);
    }

    /** Admin reset (also under the write lock). Returns false if readers never left. */
    public boolean overwrite(String newText, long newVersion, long waitMs) throws InterruptedException {
        if (!rw.tryWriteLock(waitMs)) return false;
        try {
            text = newText;
            version = newVersion;
            synchronized (this) {
                editor = null;
                leaseUntil = 0;
            }
        } finally {
            rw.writeUnlock();
        }
        return true;
    }
}
