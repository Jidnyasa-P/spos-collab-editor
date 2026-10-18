package com.spos.editor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** REST endpoints. Every mutation goes through Section, which enforces the locking rules. */
public class ApiHandler implements HttpHandler {

    private static final int MAX_BODY = 64 * 1024;
    private static final String ALLOW_ORIGIN = Config.env("ALLOWED_ORIGIN", "*");

    private final DocumentStore store;
    private final Broadcaster bc;

    public ApiHandler(DocumentStore store, Broadcaster bc) {
        this.store = store;
        this.bc = bc;
    }

    /** Thrown to produce a clean HTTP error. */
    private static final class HttpError extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        HttpError(int status, String code) { super(code); this.status = status; }
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOW_ORIGIN);
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/api/health" -> reply(ex, 200, "{\"result\":\"OK\"}");
                case "/api/doc"    -> { need(method, "GET");  reply(ex, 200, docJson()); }
                case "/api/status" -> { need(method, "GET");  reply(ex, 200, statusJson()); }
                case "/api/events" -> { need(method, "GET");  bc.add(ex); }   // stays open
                case "/api/claim"   -> { need(method, "POST"); claim(ex, params(ex)); }
                case "/api/renew"   -> { need(method, "POST"); renew(ex, params(ex)); }
                case "/api/release" -> { need(method, "POST"); release(ex, params(ex)); }
                case "/api/commit"  -> { need(method, "POST"); commit(ex, params(ex)); }
                case "/api/config"  -> { need(method, "POST"); config(ex, params(ex)); }
                case "/api/reset"   -> { need(method, "POST"); reset(ex, params(ex)); }
                default -> throw new HttpError(404, "NOT_FOUND");
            }
        } catch (HttpError e) {
            reply(ex, e.status, "{\"result\":" + Util.json(e.getMessage()) + "}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply(ex, 503, "{\"result\":\"INTERRUPTED\"}");
        } catch (Exception e) {
            e.printStackTrace();
            reply(ex, 500, "{\"result\":\"SERVER_ERROR\"}");
        }
    }

    // --------------------------------------------------------------- endpoints
    private void claim(HttpExchange ex, Map<String, String> p) throws Exception {
        String user = user(p);
        Section s = section(p);
        Section.Claim c = s.claim(user);
        if (c.granted()) {
            if (!c.alreadyMine()) store.log(user + " started editing \"" + s.title + "\"");
            bc.broadcast("status");
            reply(ex, 200, "{\"result\":\"OK\"}");
        } else {
            store.log(user + " tried to edit \"" + s.title + "\" but " + c.holder() + " already has it");
            bc.broadcast("status");
            reply(ex, 409, "{\"result\":\"LOCKED\",\"holder\":" + Util.json(c.holder()) + "}");
        }
    }

    private void renew(HttpExchange ex, Map<String, String> p) throws Exception {
        boolean ok = section(p).renew(user(p));
        reply(ex, ok ? 200 : 403, "{\"result\":\"" + (ok ? "OK" : "EXPIRED") + "\"}");
    }

    private void release(HttpExchange ex, Map<String, String> p) throws Exception {
        String user = user(p);
        Section s = section(p);
        if (s.release(user)) {
            store.log(user + " stopped editing \"" + s.title + "\" without saving");
            bc.broadcast("status");
        }
        reply(ex, 200, "{\"result\":\"OK\"}");
    }

    private void commit(HttpExchange ex, Map<String, String> p) throws Exception {
        String user = user(p);
        Section s = section(p);
        String text = p.getOrDefault("text", "").replace("\r\n", "\n").replace("\r", "\n");
        if (text.length() > Config.MAX_TEXT) throw new HttpError(413, "TOO_LONG");

        Section.Commit c = s.commit(user, text);
        switch (c.result()) {
            case OK -> {
                String waited = c.waitedMs() > 50 ? " (waited " + c.waitedMs() + " ms for readers)" : "";
                store.log(user + " saved \"" + s.title + "\" as version " + c.version() + waited);
                store.save();
                bc.broadcast("doc");
                bc.broadcast("status");
                reply(ex, 200, "{\"result\":\"OK\",\"version\":" + c.version() + ",\"waitedMs\":" + c.waitedMs() + "}");
            }
            case BUSY -> {
                store.log(user + "'s save of \"" + s.title + "\" timed out: readers kept the section busy");
                bc.broadcast("status");
                reply(ex, 503, "{\"result\":\"BUSY\"}");
            }
            case FORBIDDEN -> reply(ex, 403, "{\"result\":\"FORBIDDEN\"}");
        }
    }

    private void config(HttpExchange ex, Map<String, String> p) throws Exception {
        String user = user(p);
        try {
            if (p.containsKey("readHoldMs"))  Config.readHoldMs  = Config.clamp(Long.parseLong(p.get("readHoldMs")));
            if (p.containsKey("writeHoldMs")) Config.writeHoldMs = Config.clamp(Long.parseLong(p.get("writeHoldMs")));
        } catch (NumberFormatException e) {
            throw new HttpError(400, "BAD_NUMBER");
        }
        store.log(user + " set slow motion: reads hold " + Config.readHoldMs + " ms, saves hold " + Config.writeHoldMs + " ms");
        bc.broadcast("status");
        reply(ex, 200, "{\"result\":\"OK\"}");
    }

    private void reset(HttpExchange ex, Map<String, String> p) throws Exception {
        String user = user(p);
        int skipped = store.resetAll();
        store.log(user + " reset the document" + (skipped > 0 ? " (" + skipped + " section(s) were busy)" : ""));
        bc.broadcast("doc");
        bc.broadcast("status");
        reply(ex, 200, "{\"result\":\"OK\",\"skipped\":" + skipped + "}");
    }

    // -------------------------------------------------------------------- JSON
    private String docJson() throws InterruptedException {
        StringBuilder sb = new StringBuilder("{\"sections\":[");
        boolean first = true;
        for (Section.Snap s : store.snapshotAll()) {      // each goes through its READ lock
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(s.id())
              .append(",\"title\":").append(Util.json(s.title()))
              .append(",\"text\":").append(Util.json(s.text()))
              .append(",\"version\":").append(s.version()).append('}');
        }
        return sb.append("]}").toString();
    }

    private String statusJson() {
        StringBuilder sb = new StringBuilder("{\"online\":").append(bc.count())
            .append(",\"config\":{\"readHoldMs\":").append(Config.readHoldMs)
            .append(",\"writeHoldMs\":").append(Config.writeHoldMs)
            .append(",\"writeWaitMs\":").append(Config.writeWaitMs).append("},\"sections\":[");
        boolean first = true;
        for (Section.Status s : store.statusAll()) {      // non-blocking probes
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(s.id())
              .append(",\"editor\":").append(Util.json(s.editor()))
              .append(",\"leaseLeftMs\":").append(s.leaseLeftMs())
              .append(",\"readers\":").append(s.readers())
              .append(",\"waitingWriters\":").append(s.waitingWriters())
              .append(",\"writerActive\":").append(s.writerActive())
              .append(",\"version\":").append(s.version()).append('}');
        }
        sb.append("],\"log\":[");
        first = true;
        List<DocumentStore.LogEntry> entries = store.recentLog();
        for (DocumentStore.LogEntry e : entries) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"t\":").append(e.time()).append(",\"msg\":").append(Util.json(e.msg())).append('}');
        }
        return sb.append("]}").toString();
    }

    // ----------------------------------------------------------------- helpers
    private static void need(String actual, String expected) throws HttpError {
        if (!actual.equals(expected)) throw new HttpError(405, "METHOD_NOT_ALLOWED");
    }

    private static String user(Map<String, String> p) throws HttpError {
        String u = p.get("user");
        if (!Util.validUser(u)) throw new HttpError(400, "BAD_USER");
        return u.trim();
    }

    private Section section(Map<String, String> p) throws HttpError {
        try {
            Section s = store.get(Integer.parseInt(p.getOrDefault("section", "")));
            if (s == null) throw new HttpError(404, "NO_SUCH_SECTION");
            return s;
        } catch (NumberFormatException e) {
            throw new HttpError(400, "BAD_SECTION");
        }
    }

    private static Map<String, String> params(HttpExchange ex) throws IOException, HttpError {
        Map<String, String> m = Util.parseForm(ex.getRequestURI().getRawQuery());
        if (ex.getRequestMethod().equals("POST")) {
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) throw new HttpError(413, "TOO_LARGE");
            m.putAll(Util.parseForm(new String(body, StandardCharsets.UTF_8)));
        }
        return m;
    }

    static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
