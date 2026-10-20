package com.spos.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Who has a page open right now (browsers say hello every few seconds). */
public class Presence {
    private static final long WINDOW_MS = 12_000;
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    public void touch(String user) { seen.put(user, System.currentTimeMillis()); }

    public List<String> names() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        seen.values().removeIf(t -> t < cutoff);
        List<String> out = new ArrayList<>(seen.keySet());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
