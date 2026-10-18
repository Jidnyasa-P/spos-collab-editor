package com.spos.editor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load test against a RUNNING server (local or deployed).
 *
 *   java -cp out com.spos.editor.HttpLoadTest [baseUrl] [readers] [writers] [seconds]
 *   java -cp out com.spos.editor.HttpLoadTest http://localhost:8080 30 6 8
 *
 * Check: the total of all section versions must grow by exactly the number of
 * commits the server answered with 200. Anything else means a lost update.
 */
public class HttpLoadTest {
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static String base;

    public static void main(String[] args) throws Exception {
        base = (args.length > 0 ? args[0] : "http://localhost:8080").replaceAll("/+$", "");
        int readers = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int writers = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 8;

        long before = totalVersions();
        System.out.printf("Target %s | %d readers, %d writers, %d s | versions before = %d%n",
                base, readers, writers, seconds, before);

        long end = System.currentTimeMillis() + seconds * 1000L;
        AtomicLong reads = new AtomicLong(), readFail = new AtomicLong(), maxMs = new AtomicLong(), totalMs = new AtomicLong();
        AtomicInteger commits = new AtomicInteger(), locked = new AtomicInteger(), busy = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);

        for (int i = 0; i < readers; i++) pool.submit(() -> {
            while (System.currentTimeMillis() < end) {
                long t0 = System.nanoTime();
                try {
                    int code = send("GET", "/api/doc", null).statusCode();
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    if (code == 200) { reads.incrementAndGet(); totalMs.addAndGet(ms); maxMs.accumulateAndGet(ms, Math::max); }
                    else readFail.incrementAndGet();
                } catch (Exception e) { readFail.incrementAndGet(); }
            }
        });
        for (int i = 0; i < writers; i++) {
            final String user = "load-w" + i;
            final Random rnd = new Random(i);
            pool.submit(() -> {
                while (System.currentTimeMillis() < end) {
                    try {
                        int sec = 1 + rnd.nextInt(4);
                        int c = send("POST", "/api/claim", form("user", user, "section", sec)).statusCode();
                        if (c == 409) { locked.incrementAndGet(); Thread.sleep(20); continue; }
                        if (c != 200) continue;
                        int r = send("POST", "/api/commit", form("user", user, "section", sec, "text", user + " wrote at " + System.nanoTime())).statusCode();
                        if (r == 200) commits.incrementAndGet();
                        else { if (r == 503) busy.incrementAndGet(); send("POST", "/api/release", form("user", user, "section", sec)); }
                    } catch (Exception e) { /* count nothing; connection hiccup */ }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(seconds + 60, TimeUnit.SECONDS);

        long after = totalVersions();
        long n = Math.max(1, reads.get());
        System.out.printf("Reads ok = %d (failed %d), avg %d ms, max %d ms%n", reads.get(), readFail.get(), totalMs.get() / n, maxMs.get());
        System.out.printf("Commits ok = %d, claims refused (locked) = %d, commits busy = %d%n", commits.get(), locked.get(), busy.get());
        System.out.printf("Versions after = %d (growth %d)%n", after, after - before);
        boolean pass = (after - before) == commits.get() && readFail.get() == 0;
        System.out.println(pass ? "RESULT: PASS (version growth equals successful commits)" : "RESULT: FAIL");
        System.exit(pass ? 0 : 1);
    }

    static long totalVersions() throws Exception {
        String body = send("GET", "/api/status", null).body();
        Matcher m = Pattern.compile("\"version\":(\\d+)").matcher(body);
        long sum = 0;
        while (m.find()) sum += Long.parseLong(m.group(1));
        return sum;
    }

    static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (method.equals("POST")) {
            b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.GET();
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String form(Object... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(kv[i]).append('=').append(URLEncoder.encode(String.valueOf(kv[i + 1]), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
