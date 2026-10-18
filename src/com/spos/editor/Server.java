package com.spos.editor;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/** Entry point: wires the store, the API, the static files and the live-update hub together. */
public class Server {
    public static void main(String[] args) throws Exception {
        int port = (int) Config.envLong("PORT", 8080);
        Path staticDir = Path.of(Config.env("STATIC_DIR", "static"));

        DocumentStore store = DocumentStore.createDefault();
        Broadcaster bc = new Broadcaster();

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        // One thread per request: this is what makes the concurrency real.
        http.setExecutor(Executors.newCachedThreadPool());
        http.createContext("/api/", new ApiHandler(store, bc));
        http.createContext("/", new StaticHandler(staticDir));
        http.start();

        System.out.println("Shared Notes is running.");
        System.out.println("Open http://localhost:" + port + " in two or more browser windows.");
        System.out.println("Press Ctrl+C to stop.");
    }
}
