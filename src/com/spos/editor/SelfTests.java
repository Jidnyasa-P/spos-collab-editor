package com.spos.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free test suite. Run with:  java -cp out com.spos.editor.SelfTests
 * Exit code is 0 when every test passes, 1 otherwise.
 */
public class SelfTests {
    private static int passed = 0, failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", name);
    }

    private static Thread start(ThrowingRunnable r) {
        Thread t = new Thread(() -> {
            try { r.run(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.start();
        return t;
    }

    private interface ThrowingRunnable { void run() throws InterruptedException; }

    public static void main(String[] args) throws Exception {
        System.out.println("Lock behaviour");
        readersShareTheLock();
        readerOvertakesWaitingWriter();
        writerExcludesReaders();
        writerTimesOutWhileReadersStay();

        System.out.println("Edit claims and commits");
        claimRules();
        commitRules();
        leaseExpiry();
        commitWaitsForReaders();

        System.out.println("Helpers and storage");
        helpers();
        persistence();

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ lock
    static void readersShareTheLock() throws Exception {
        ReaderPriorityLock lock = new ReaderPriorityLock();
        AtomicInteger inside = new AtomicInteger(), max = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] ts = new Thread[6];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = start(() -> {
                go.await();
                lock.readLock();
                max.accumulateAndGet(inside.incrementAndGet(), Math::max);
                Thread.sleep(150);
                inside.decrementAndGet();
                lock.readUnlock();
            });
        }
        go.countDown();
        for (Thread t : ts) t.join();
        check("TC1 six readers are inside together (max inside = " + max.get() + ")", max.get() == 6);
    }

    static void readerOvertakesWaitingWriter() throws Exception {
        ReaderPriorityLock lock = new ReaderPriorityLock();
        lock.readLock();                                   // Reader-1 inside
        AtomicInteger writerGotIn = new AtomicInteger();
        Thread writer = start(() -> {
            if (lock.tryWriteLock(5000)) { writerGotIn.set(1); lock.writeUnlock(); }
        });
        while (lock.waitingWriters() == 0) Thread.sleep(5);   // writer is now waiting
        long t0 = System.nanoTime();
        lock.readLock();                                   // Reader-2 arrives LATER
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("TC2 reader overtakes a waiting writer (entered in " + ms + " ms)", ms < 100 && writerGotIn.get() == 0);
        lock.readUnlock();
        Thread.sleep(50);
        check("TC2b writer still waits while a reader remains", writerGotIn.get() == 0);
        lock.readUnlock();                                 // last reader leaves
        writer.join();
        check("TC2c writer enters after the last reader leaves", writerGotIn.get() == 1);
    }

    static void writerExcludesReaders() throws Exception {
        ReaderPriorityLock lock = new ReaderPriorityLock();
        lock.tryWriteLock(1000);
        AtomicInteger readerIn = new AtomicInteger();
        Thread reader = start(() -> { lock.readLock(); readerIn.set(1); lock.readUnlock(); });
        Thread.sleep(150);
        check("TC3 reader blocked while a writer is inside", readerIn.get() == 0 && lock.isWriterActive());
        lock.writeUnlock();
        reader.join();
        check("TC3b reader proceeds after the writer leaves", readerIn.get() == 1);
    }

    static void writerTimesOutWhileReadersStay() throws Exception {
        ReaderPriorityLock lock = new ReaderPriorityLock();
        lock.readLock();
        boolean got = lock.tryWriteLock(100);
        lock.readUnlock();
        check("TC4 writer times out (does not hang) while a reader stays", !got);
    }

    // ---------------------------------------------------------------- claims
    static void claimRules() {
        Section a = new Section(1, "A", "x");
        Section b = new Section(2, "B", "y");
        check("TC5 free section can be claimed", a.claim("asha").granted());
        Section.Claim denied = a.claim("ravi");
        check("TC6 second user is denied and told who holds it", !denied.granted() && denied.holder().equals("asha"));
        check("TC6b same user can claim again", a.claim("asha").granted());
        check("TC7 a different section is independent", b.claim("ravi").granted());
    }

    static void commitRules() throws Exception {
        Section s = new Section(1, "A", "old");
        check("TC8 commit without claim is forbidden", s.commit("asha", "new").result() == Section.Result.FORBIDDEN);
        s.claim("asha");
        check("TC9 other user cannot commit", s.commit("ravi", "hack").result() == Section.Result.FORBIDDEN);
        Section.Commit c = s.commit("asha", "new");
        Section.Snap snap = s.snapshot();
        check("TC10 commit succeeds, text changes, version increases",
              c.result() == Section.Result.OK && snap.text().equals("new") && snap.version() == 1);
        check("TC10b claim is released after saving", s.currentEditor() == null);
    }

    static void leaseExpiry() throws Exception {
        Section s = new Section(1, "A", "x", 0, 150);
        s.claim("asha");
        check("TC11 other user denied before lease ends", !s.claim("ravi").granted());
        Thread.sleep(220);
        check("TC11b other user can claim after lease expires", s.claim("ravi").granted());
        check("TC11c expired user cannot commit", s.commit("asha", "late").result() == Section.Result.FORBIDDEN);

        Section r = new Section(2, "B", "x", 0, 300);
        r.claim("asha");
        Thread.sleep(200);
        boolean renewed = r.renew("asha");
        Thread.sleep(200);                                 // 400 ms after claim, but renewed at 200
        check("TC12 heartbeat renew keeps the claim alive", renewed && "asha".equals(r.currentEditor()));
    }

    static void commitWaitsForReaders() throws Exception {
        Section s = new Section(1, "A", "old");
        s.claim("asha");
        Thread reader = start(() -> { s.rw.readLock(); Thread.sleep(300); s.rw.readUnlock(); });
        Thread.sleep(50);
        Section.Commit c = s.commit("asha", "new");
        reader.join();
        check("TC13 commit waits for the reader, then succeeds (waited " + c.waitedMs() + " ms)",
              c.result() == Section.Result.OK && c.waitedMs() >= 150);

        long saved = Config.writeWaitMs;
        Config.writeWaitMs = 100;
        Section t = new Section(2, "B", "old");
        t.claim("ravi");
        t.rw.readLock();
        Section.Commit busy = t.commit("ravi", "new");
        t.rw.readUnlock();
        Config.writeWaitMs = saved;
        check("TC14 commit answers BUSY if readers never leave", busy.result() == Section.Result.BUSY);
    }

    // --------------------------------------------------------------- helpers
    static void helpers() {
        check("TC15 valid and invalid user names",
              Util.validUser("Asha_1") && Util.validUser("Ravi K.") && !Util.validUser("") && !Util.validUser(null)
              && !Util.validUser("a".repeat(21)) && !Util.validUser("bad<script>"));
        check("TC16 JSON escaping of quotes, newlines, control characters",
              Util.json("a\"b\\c\nd\u0001").equals("\"a\\\"b\\\\c\\nd\\u0001\""));
        check("TC17 form parsing decodes + and %XX",
              Util.parseForm("user=Asha&text=a+b%0Ac").get("text").equals("a b\nc"));
        check("TC18 data-file escaping round trip",
              Util.unescapeLine(Util.escapeLine("x\ty\nz\\w")).equals("x\ty\nz\\w"));
    }

    static void persistence() throws Exception {
        Path dir = Files.createTempDirectory("spos-test");
        Path file = dir.resolve("doc.db");
        DocumentStore one = new DocumentStore(file, 60_000);
        Section s = one.get(2);
        s.claim("asha");
        s.commit("asha", "line1\nline2 with \"quotes\"\tand tab");
        one.save();
        DocumentStore two = new DocumentStore(file, 60_000);
        Section.Snap loaded = two.get(2).snapshot();
        check("TC19 saved text and version survive a restart",
              loaded.version() == 1 && loaded.text().equals("line1\nline2 with \"quotes\"\tand tab"));
        two.resetAll();
        check("TC20 reset restores the seed text and version 0",
              two.get(2).snapshot().version() == 0 && two.get(2).snapshot().text().equals(DocumentStore.SEED[1][1]));
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
    }
}
