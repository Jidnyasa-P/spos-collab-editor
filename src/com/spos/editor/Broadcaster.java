package com.spos.editor;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

/** Server-Sent Events hub: pushes small "something changed" messages to every browser. */
public class Broadcaster {

    private static final class Client {
        final HttpExchange ex;
        final OutputStream os;
        Client(HttpExchange ex, OutputStream os) { this.ex = ex; this.os = os; }
    }

    private final List<Client> clients = new CopyOnWriteArrayList<>();

    public Broadcaster() {
        ScheduledExecutorService ping = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-ping");
            t.setDaemon(true);
            return t;
        });
        // A comment line keeps proxies from closing idle streams and prunes dead clients.
        ping.scheduleAtFixedRate(() -> send(": ping\n\n"), 20, 20, TimeUnit.SECONDS);
    }

    public void add(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);          // chunked, stays open
        Client c = new Client(ex, ex.getResponseBody());
        write(c, "retry: 2000\n: connected\n\n");
        clients.add(c);
    }

    public void broadcast(String event) {
        send("event: " + event + "\ndata: 1\n\n");
    }

    public int count() { return clients.size(); }

    private void send(String message) {
        for (Client c : clients) write(c, message);
    }

    private void write(Client c, String message) {
        synchronized (c) {                       // never interleave two messages
            try {
                c.os.write(message.getBytes(StandardCharsets.UTF_8));
                c.os.flush();
            } catch (IOException e) {
                clients.remove(c);
                try { c.ex.close(); } catch (Exception ignored) { }
            }
        }
    }
}
