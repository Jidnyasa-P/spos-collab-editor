package com.spos.editor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Serves the browser files from the static/ folder (with path-traversal protection). */
public class StaticHandler implements HttpHandler {

    private static final Map<String, String> TYPES = Map.of(
        "html", "text/html; charset=utf-8",
        "css",  "text/css; charset=utf-8",
        "js",   "text/javascript; charset=utf-8",
        "json", "application/json; charset=utf-8",
        "svg",  "image/svg+xml",
        "png",  "image/png",
        "ico",  "image/x-icon");

    private final Path root;

    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        if (!m.equals("GET") && !m.equals("HEAD")) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        String uri = ex.getRequestURI().getPath();
        if (uri.equals("/")) uri = "/index.html";
        Path file = root.resolve(uri.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            ApiHandler.reply(ex, 404, "{\"result\":\"NOT_FOUND\"}");
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, m.equals("HEAD") ? -1 : data.length);
        if (!m.equals("HEAD")) {
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        } else {
            ex.close();
        }
    }
}
