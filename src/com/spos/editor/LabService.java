package com.spos.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs Race Lab sessions for the browser. The browser starts a run and then polls for new events,
 * so it works through any proxy or tunnel (no streaming connection needed).
 */
public class LabService {

    private static final int MAX_ACTIVE = 3;

    private static final class Session {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final long created = System.currentTimeMillis();
        final List<String> events = new ArrayList<>();      // guarded by this
        volatile String statsUnsafe = "null", statsSafe = "null";
        volatile String resultUnsafe = "null", resultSafe = "null";
        volatile boolean done = false, cancelled = false;
        volatile RaceLab.Params params;

        synchronized void add(String json) {
            if (events.size() < 6000) events.add(json);
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();

    /** Returns JSON, or null if too many runs are already active. */
    public String start(RaceLab.Params p) {
        long cutoff = System.currentTimeMillis() - 10 * 60_000L;
        sessions.values().removeIf(s -> s.created < cutoff);
        if (active.get() >= MAX_ACTIVE) return null;

        Session s = new Session();
        s.params = p;
        sessions.put(s.id, s);
        active.incrementAndGet();

        RaceLab.Listener listener = new RaceLab.Listener() {
            public void actor(boolean safe, String who, String state) {
                s.add("{\"s\":\"" + tag(safe) + "\",\"k\":\"actor\",\"who\":" + Util.json(who) + ",\"state\":" + Util.json(state) + "}");
            }
            public void log(boolean safe, String kind, String text) {
                s.add("{\"s\":\"" + tag(safe) + "\",\"k\":\"log\",\"kind\":" + Util.json(kind) + ",\"text\":" + Util.json(text) + "}");
            }
            public void stats(boolean safe, int counter, int opsDone, int expected, int lost, int reads,
                              int inconsistent, int readersInside, int writersInside, int waiting) {
                String j = "{\"counter\":" + counter + ",\"opsDone\":" + opsDone + ",\"expected\":" + expected
                        + ",\"lost\":" + lost + ",\"reads\":" + reads + ",\"inconsistent\":" + inconsistent
                        + ",\"readersInside\":" + readersInside + ",\"writersInside\":" + writersInside
                        + ",\"waiting\":" + waiting + "}";
                if (safe) s.statsSafe = j; else s.statsUnsafe = j;
            }
        };

        Thread master = new Thread(() -> {
            try {
                Thread u = new Thread(() -> runOne(false, p, listener, s), "lab-unsafe");
                Thread f = new Thread(() -> runOne(true, p, listener, s), "lab-safe");
                u.setDaemon(true); f.setDaemon(true);
                u.start(); f.start();
                u.join(); f.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                s.done = true;
                active.decrementAndGet();
            }
        }, "lab-master");
        master.setDaemon(true);
        master.start();

        return "{\"id\":\"" + s.id + "\",\"writers\":" + p.writers() + ",\"readers\":" + p.readers()
                + ",\"ops\":" + p.ops() + ",\"stepMs\":" + p.stepMs() + ",\"expected\":" + p.expected()
                + ",\"busy\":" + p.busyReaders() + "}";
    }

    private static String tag(boolean safe) { return safe ? "safe" : "unsafe"; }

    private void runOne(boolean safe, RaceLab.Params p, RaceLab.Listener l, Session s) {
        try {
            RaceLab.Result r = RaceLab.run(safe, p, l, () -> s.cancelled);
            String j = "{\"expected\":" + r.expected() + ",\"counter\":" + r.finalCounter() + ",\"mirror\":" + r.finalMirror()
                    + ",\"lost\":" + r.lost() + ",\"reads\":" + r.reads() + ",\"inconsistent\":" + r.inconsistentReads()
                    + ",\"maxReaders\":" + r.maxReadersTogether() + ",\"elapsedMs\":" + r.elapsedMs() + "}";
            if (safe) s.resultSafe = j; else s.resultUnsafe = j;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Events after position "since"; the browser passes back "next" from the previous answer. */
    public String poll(String id, int since) {
        Session s = sessions.get(id);
        if (s == null) return null;
        StringBuilder sb = new StringBuilder("{\"events\":[");
        int next;
        synchronized (s) {
            next = s.events.size();
            boolean first = true;
            for (int i = Math.max(0, since); i < next; i++) {
                if (!first) sb.append(',');
                first = false;
                sb.append(s.events.get(i));
            }
        }
        boolean done = s.done;
        sb.append("],\"next\":").append(next)
          .append(",\"done\":").append(done)
          .append(",\"stats\":{\"unsafe\":").append(s.statsUnsafe).append(",\"safe\":").append(s.statsSafe).append('}')
          .append(",\"results\":{\"unsafe\":").append(s.resultUnsafe).append(",\"safe\":").append(s.resultSafe).append("}}");
        return sb.toString();
    }

    public boolean stop(String id) {
        Session s = sessions.get(id);
        if (s == null) return false;
        s.cancelled = true;
        return true;
    }
}
