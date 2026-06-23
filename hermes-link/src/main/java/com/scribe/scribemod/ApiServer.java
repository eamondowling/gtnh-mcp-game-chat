package com.scribe.scribemod;

import com.scribe.scribemod.api.*;
import com.scribe.scribemod.chat.ChatListener;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.Headers;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

public class ApiServer {
    private final int port;
    private final String authToken;
    private final boolean allowActions;
    private HttpServer server;

    public ApiServer(int port, String authToken, boolean allowActions) {
        this.port = port;
        this.authToken = authToken;
        this.allowActions = allowActions;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));

            server.createContext("/", new RootHandler());
            server.createContext("/player", new PlayerHandler());
            server.createContext("/player/inventory", new PlayerHandler.InventoryHandler());
            server.createContext("/player/nearby", new PlayerHandler.NearbyHandler());
            server.createContext("/world/block", new WorldHandler.BlockHandler());
            server.createContext("/world/blocks", new WorldHandler.BlocksHandler());
            server.createContext("/action/chat", new ActionHandler.ChatHandler(allowActions));
            server.createContext("/action/click", new ActionHandler.ClickHandler(allowActions));
            server.createContext("/action/move", new ActionHandler.MoveHandler(allowActions));
            server.createContext("/action/book", new BookHandler(allowActions));
            server.createContext("/chat/history", new ChatHistoryHandler());
            server.createContext("/chat/poll", new ChatPollHandler());

            server.start();
        } catch (IOException e) {
            ScribeMod.LOG.error("Failed to start API server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // --- Auth helper used by all handlers ---

    public static boolean checkAuth(HttpExchange exchange, String authToken) {
        if (authToken.isEmpty()) return true;
        Headers headers = exchange.getRequestHeaders();
        String auth = headers.getFirst("Authorization");
        return auth != null && auth.equals("Bearer " + authToken);
    }

    public static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    public static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        sendJson(exchange, code, "{\"error\":\"" + escapeJson(message) + "\"}");
    }

    public static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // --- Root handler: health check + API index ---

    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            sendJson(exchange, 200,
                "{" +
                "\"mod\":\"scribe\"," +
                "\"version\":\"" + ScribeMod.VERSION + "\"," +
                "\"endpoints\":[" +
                "\"GET /\"," +
                "\"GET /player\"," +
                "\"GET /player/inventory\"," +
                "\"GET /player/nearby?radius=10\"," +
                "\"GET /world/block?x=0&y=64&z=0\"," +
                "\"GET /world/blocks?x=0&y=64&z=0&radius=5\"," +
                "\"GET /chat/history\"," +
                "\"GET /chat/poll?since=0&timeout=30000\"," +
                "\"POST /action/chat\"," +
                "\"POST /action/click\"," +
                "\"POST /action/move\"," +
                "\"POST /action/book\"" +
                "]" +
                "}"
            );
        }
    }

    // --- Chat history handler ---

    static class ChatHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            List<ChatListener.ChatMessage> messages = ChatListener.getHistory();
            StringBuilder sb = new StringBuilder("{\"messages\":[");
            boolean first = true;
            for (ChatListener.ChatMessage msg : messages) {
                if (!first) sb.append(",");
                first = false;
                sb.append(msg.toJson());
            }
            sb.append("]}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    // --- Chat poll handler (long-poll for new messages) ---

    static class ChatPollHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }

            long since = 0;
            long timeout = 30000;
            String qSince = getQueryParam(exchange, "since");
            String qTimeout = getQueryParam(exchange, "timeout");
            if (qSince != null) {
                try { since = Long.parseLong(qSince); } catch (NumberFormatException ignored) {}
            }
            if (qTimeout != null) {
                try { timeout = Long.parseLong(qTimeout); } catch (NumberFormatException ignored) {}
            }

            List<ChatListener.ChatMessage> messages = ChatListener.pollNewMessages(since, timeout);
            boolean timedOut = messages.isEmpty();

            StringBuilder sb = new StringBuilder("{\"messages\":[");
            boolean first = true;
            for (ChatListener.ChatMessage msg : messages) {
                if (!first) sb.append(",");
                first = false;
                sb.append(msg.toJson());
            }
            sb.append("],\"timed_out\":").append(timedOut).append("}");
            sendJson(exchange, 200, sb.toString());
        }
    }

    // --- Query param helper ---

    static String getQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception e) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}
