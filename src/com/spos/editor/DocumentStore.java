package com.spos.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns all sections, the activity log and the optional data file. */
public class DocumentStore {

    public record LogEntry(long time, String msg) {}

    static final String[][] SEED = {
        {"What this page is",
         "This document is kept by a small Java server that applies the classic readers-writers solution with reader priority.\n"
       + "Everyone can read every section at the same moment. Press Edit on a section to claim it; while you hold the claim, nobody else can change that section."},
        {"The rules",
         "1. Any number of readers may be inside a section together.\n"
       + "2. A writer needs the section completely to itself.\n"
       + "3. Readers never wait for a writer that is only waiting. They wait only while a writer is actually saving."},
        {"Reader priority and its price",
         "While at least one reader is inside, new readers walk straight in and a waiting writer has to wait. "
       + "The price is starvation: if readers never stop arriving, a writer may never get in. "
       + "Turn on Slow motion in the demo controls to watch this happen."},
        {"Your turn",
         "Open this page in a second window under a different name. Claim two different sections, then try to claim the same one from both windows. Edit this text and save it."}
    };

    private final List<Section> sections = new ArrayList<>();
    private final Path dataFile;               // null = no persistence
    private final Deque<LogEntry> log = new ArrayDeque<>();
    private final ExecutorService readPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "doc-reader");
        t.setDaemon(true);
        return t;
    });

    public DocumentStore(Path dataFile, long leaseMs) {
        this.dataFile = dataFile;
        Map<Integer, String[]> saved = loadFile();
        for (int i = 0; i < SEED.length; i++) {
            int id = i + 1;
            String text = SEED[i][1];
            long version = 0;
            String[] s = saved.get(id);
            if (s != null) {
                version = Long.parseLong(s[0]);
                text = s[1];
            }
            sections.add(new Section(id, SEED[i][0], text, version, leaseMs));
        }
        log("Server started with " + sections.size() + " sections");
    }

    public static DocumentStore createDefault() {
        String f = Config.env("DATA_FILE", "data/document.db");
        Path p = f.equalsIgnoreCase("none") ? null : Paths.get(f);
        return new DocumentStore(p, Config.LEASE_MS);
    }

    public List<Section> sections() { return sections; }

    public Section get(int id) {
        return (id >= 1 && id <= sections.size()) ? sections.get(id - 1) : null;
    }

    /** Reads every section in parallel, each through its own read lock. */
    public List<Section.Snap> snapshotAll() throws InterruptedException {
        List<Callable<Section.Snap>> jobs = new ArrayList<>();
        for (Section s : sections) jobs.add(s::snapshot);
        List<Section.Snap> out = new ArrayList<>();
        try {
            for (Future<Section.Snap> f : readPool.invokeAll(jobs)) out.add(f.get());
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
        return out;
    }

    public List<Section.Status> statusAll() {
        List<Section.Status> out = new ArrayList<>();
        for (Section s : sections) out.add(s.status());
        return out;
    }

    // ------------------------------------------------------------ activity log
    public void log(String msg) {
        synchronized (log) {
            log.addFirst(new LogEntry(System.currentTimeMillis(), msg));
            while (log.size() > 40) log.removeLast();
        }
    }

    public List<LogEntry> recentLog() {
        synchronized (log) { return new ArrayList<>(log); }
    }

    // ------------------------------------------------------------- reset / save
    /** Puts every section back to its seed text. Returns how many could not be reset. */
    public int resetAll() throws InterruptedException {
        int skipped = 0;
        for (int i = 0; i < sections.size(); i++) {
            if (!sections.get(i).overwrite(SEED[i][1], 0, 2000)) skipped++;
        }
        save();
        return skipped;
    }

    public synchronized void save() {
        if (dataFile == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Section.Snap s : snapshotAll()) {
                sb.append(s.id()).append('\t').append(s.version()).append('\t')
                  .append(Util.escapeLine(s.text())).append('\n');
            }
            Path parent = dataFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Could not save document: " + e.getMessage());
        }
    }

    private Map<Integer, String[]> loadFile() {
        Map<Integer, String[]> m = new HashMap<>();
        if (dataFile == null || !Files.exists(dataFile)) return m;
        try {
            for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 3);
                if (p.length == 3) {
                    try {
                        m.put(Integer.parseInt(p[0]), new String[]{String.valueOf(Long.parseLong(p[1])), Util.unescapeLine(p[2])});
                    } catch (NumberFormatException ignored) { /* skip bad line */ }
                }
            }
        } catch (IOException e) {
            System.err.println("Could not read data file, starting fresh: " + e.getMessage());
        }
        return m;
    }
}
