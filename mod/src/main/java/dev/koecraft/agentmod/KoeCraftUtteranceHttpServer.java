package dev.koecraft.agentmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;

final class KoeCraftUtteranceHttpServer {
    private final int port;
    private final KoeCraftRecognizedTextProcessor processor;
    private final Consumer<String> statusSink;
    private final Logger logger;
    private HttpServer server;

    KoeCraftUtteranceHttpServer(int port, KoeCraftRecognizedTextProcessor processor, Consumer<String> statusSink, Logger logger) {
        this.port = port;
        this.processor = processor;
        this.statusSink = statusSink;
        this.logger = logger;
    }

    void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/api/utterance", this::handleUtterance);
            server.createContext("/api/health", this::handleHealth);
            server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "KoeCraft Utterance HTTP");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            statusSink.accept("KoeCraft utterance HTTP listening on http://127.0.0.1:" + port);
        } catch (IOException error) {
            logger.warn("[KoeCraft] Utterance HTTP server failed: {}", error.toString());
            statusSink.accept("KoeCraft utterance HTTP failed on 127.0.0.1:" + port);
        }
    }

    void stopGracefully() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"ok\":true,\"service\":\"koecraft_mod_utterance\"}");
    }

    private void handleUtterance(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String text = parseRecognizedText(body);
        if (text.isBlank()) {
            respond(exchange, 400, "{\"ok\":false,\"error\":\"missing_recognized_text\"}");
            return;
        }
        statusSink.accept("KoeCraft bridge recognized: " + text);
        processor.handleRecognizedText(text);
        respond(exchange, 202, "{\"ok\":true}");
    }

    private String parseRecognizedText(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "{}" : body);
            if (!parsed.isJsonObject()) {
                return "";
            }
            JsonObject root = parsed.getAsJsonObject();
            return root.has("recognized_text") && root.get("recognized_text").isJsonPrimitive()
                ? root.get("recognized_text").getAsString().trim()
                : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
