package com.spos.editor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A narrated, slow demonstration of race conditions and how the lock prevents them.
 *
 *   loaddemo.bat                 (waits for Enter between scenes; best for presenting)
 *   loaddemo.bat --auto          (no waiting)
 *   loaddemo.bat --url=http://localhost:8080   (server used by scene 4)
 *
 * Scenes 1-3 run inside this program. Scene 4 talks to a running server and is skipped if none is found.
 */
public class LoadDemo {
    private static boolean auto = false;
    private static String url = "http://localhost:8080";
    private static final RaceLab.Params PARAMS = new RaceLab.Params(4, 2, 5, 140, false);

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if (a.equals("--auto")) auto = true;
            else if (a.startsWith("--url=")) url = a.substring(6);
        }

        banner("Race conditions and the reader-priority lock: a guided demo");
        say("The setting: a shared counter (and a mirror copy of it). 4 writers each add 1 to it");
        say(PARAMS.ops() + " times, so the correct final value is " + PARAMS.expected() + ". 2 readers keep checking that the counter");
        say("and its mirror agree. Every step is slowed down so that you can follow it.");
        pause();

        RaceLab.Result unsafe = scene1();
        pause();
        RaceLab.Result safe = scene2();
        pause();
        scene3();
        pause();
        boolean http = scene4();

        banner("Summary");
        System.out.printf("  %-34s %-12s %-12s%n", "", "NO LOCK", "WITH LOCK");
        System.out.printf("  %-34s %-12s %-12s%n", "Final counter (correct = " + PARAMS.expected() + ")", unsafe.finalCounter(), safe.finalCounter());
        System.out.printf("  %-34s %-12s %-12s%n", "Updates lost", unsafe.lost(), safe.lost());
        System.out.printf("  %-34s %-12s %-12s%n", "Inconsistent reads", unsafe.inconsistentReads(), safe.inconsistentReads());
        System.out.printf("  %-34s %-12s %-12s%n", "Time taken (seconds)", String.format("%.1f", unsafe.elapsedMs() / 1000.0), String.format("%.1f", safe.elapsedMs() / 1000.0));
        System.out.println();
        say("The lock costs a little time (writers take turns) and buys correctness: no lost update and");
        say("no reader ever sees a half-finished change. That is mutual exclusion and data consistency.");
        if (!http) say("(Scene 4 was skipped because no server was running. Start run.bat to include it.)");
    }

    // ------------------------------------------------------------------ scenes
    static RaceLab.Result scene1() throws Exception {
        banner("Scene 1 of 4: WITHOUT a lock");
        say("Each writer does: read the counter, think for a moment, write back (value + 1).");
        say("If two writers read the same value, both write the same result and one increment disappears.");
        System.out.println();
        RaceLab.Result r = RaceLab.run(false, PARAMS, new ConsoleListener(), () -> false);
        System.out.println();
        System.out.println("  RESULT WITHOUT A LOCK");
        System.out.println("    Final counter : " + r.finalCounter() + "   (correct answer: " + r.expected() + ")");
        System.out.println("    Updates lost  : " + r.lost() + "   <-- silent data loss, no error was reported");
        System.out.println("    Mirror counter: " + r.finalMirror() + "   (should equal the counter)");
        System.out.println("    Inconsistent reads: " + r.inconsistentReads() + " of " + r.reads() + " reads saw a half-finished update");
        return r;
    }

    static RaceLab.Result scene2() throws Exception {
        banner("Scene 2 of 4: WITH the reader-priority lock");
        say("Same workload, same speed. This time a writer must take the write lock before it reads");
        say("and keeps it until the counter AND its mirror are updated. Readers take the read lock.");
        System.out.println();
        RaceLab.Result r = RaceLab.run(true, PARAMS, new ConsoleListener(), () -> false);
        System.out.println();
        System.out.println("  RESULT WITH THE LOCK");
        System.out.println("    Final counter : " + r.finalCounter() + "   (correct answer: " + r.expected() + ")");
        System.out.println("    Updates lost  : " + r.lost());
        System.out.println("    Mirror counter: " + r.finalMirror());
        System.out.println("    Inconsistent reads: " + r.inconsistentReads() + " of " + r.reads());
        System.out.println("    Most readers inside together: " + r.maxReadersTogether() + "  (readers do not block each other)");
        return r;
    }

    static void scene3() throws Exception {
        banner("Scene 3 of 4: reader priority");
        say("A writer is waiting. Readers that arrive LATER still go in first. The writer enters only");
        say("when the last reader has left, and readers arriving during the write must wait.");
        System.out.println();
        LockDemo.main(new String[0]);
        Thread.sleep(3800);
    }

    static boolean scene4() throws Exception {
        banner("Scene 4 of 4: the same rule over HTTP (eight users claim one section at the same moment)");
        HttpLoadTest.base = url;
        try {
            HttpLoadTest.send("GET", "/api/health", null);
        } catch (Exception e) {
            say("No server found at " + url + ". Skipping this scene.");
            return false;
        }
        int section = 3;
        int users = 8;
        say(users + " users press Edit on section " + section + " at exactly the same time...");
        AtomicInteger granted = new AtomicInteger(), refused = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] ts = new Thread[users];
        String[] winner = new String[1];
        for (int i = 0; i < users; i++) {
            final String name = "Demo-" + (i + 1);
            ts[i] = new Thread(() -> {
                try {
                    go.await();
                    int code = HttpLoadTest.send("POST", "/api/claim", HttpLoadTest.form("user", name, "section", section)).statusCode();
                    if (code == 200) { granted.incrementAndGet(); synchronized (winner) { winner[0] = name; } }
                    else if (code == 409) refused.incrementAndGet();
                } catch (Exception ignored) { }
            });
            ts[i].start();
        }
        go.countDown();
        for (Thread t : ts) t.join();
        System.out.println("    Granted: " + granted.get() + "   Refused: " + refused.get());
        say(granted.get() == 1 ? "Exactly one user got the section. Mutual exclusion holds even under a simultaneous burst."
                               : "UNEXPECTED: " + granted.get() + " users were granted the section.");
        if (winner[0] != null) {
            HttpLoadTest.send("POST", "/api/release", HttpLoadTest.form("user", winner[0], "section", section));
            say(winner[0] + " released the section without saving, so the document is unchanged.");
        }
        return true;
    }

    // ----------------------------------------------------------------- helpers
    private static void banner(String text) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println(" " + text);
        System.out.println("============================================================");
    }

    private static void say(String text) { System.out.println("  " + text); }

    private static void pause() throws Exception {
        System.out.println();
        if (auto) { Thread.sleep(1500); return; }
        System.out.print("  [Press Enter to continue] ");
        System.out.flush();
        int c;
        while ((c = System.in.read()) != -1 && c != '\n') { /* wait for Enter */ }
    }

    /** Prints a readable story from the engine's events. */
    private static final class ConsoleListener implements RaceLab.Listener {
        private final long t0 = System.nanoTime();
        private long lastStats = 0;
        private int shownInconsistent = 0;

        private synchronized void print(String s) {
            System.out.printf("  [%5.1fs] %s%n", (System.nanoTime() - t0) / 1e9, s);
        }

        public void actor(boolean safe, String who, String state) {
            if (safe && state.equals("waiting") && who.startsWith("Writer")) { /* too chatty: shown in the log text instead */ }
        }

        public void log(boolean safe, String kind, String text) {
            if (kind.equals("bad") && text.contains("mirror")) {
                if (++shownInconsistent > 3) { if (shownInconsistent == 4) print("... more inconsistent reads (counted in the result)"); return; }
                print("!! " + text);
            } else if (kind.equals("bad")) {
                print("!! LOST UPDATE: " + text);
            } else if (kind.equals("good")) {
                print("ok " + text);
            } else {
                print("   " + text);
            }
        }

        public void stats(boolean safe, int counter, int ops, int expected, int lost, int reads,
                          int inconsistent, int readersInside, int writersInside, int waiting) {
            long now = System.nanoTime();
            if (now - lastStats < 1_500_000_000L) return;
            lastStats = now;
            print(String.format("   progress: counter=%d  increments finished=%d/%d  readers inside=%d  writers waiting=%d",
                    counter, ops, expected, readersInside, waiting));
        }
    }
}
