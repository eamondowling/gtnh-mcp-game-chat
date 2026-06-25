package com.scribe.scribemod.api;

import com.scribe.scribemod.ApiServer;
import com.scribe.scribemod.ScribeMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class ActionHandler {

    // --- /action/chat ---
    // POST body: {"message": "hello world"}
    // Sends a chat message as the player.

    public static class ChatHandler implements HttpHandler {
        private final boolean enabled;

        public ChatHandler(boolean enabled) { this.enabled = enabled; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                ApiServer.sendError(exchange, 405, "Method not allowed");
                return;
            }
            if (!enabled) {
                ApiServer.sendError(exchange, 403, "Actions disabled in config");
                return;
            }
            if (!ApiServer.checkAuth(exchange, ScribeMod.authToken)) {
                ApiServer.sendError(exchange, 401, "Unauthorized");
                return;
            }

            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) {
                ApiServer.sendError(exchange, 503, "Server not running");
                return;
            }

            EntityPlayerMP player = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
            if (player == null) {
                ApiServer.sendError(exchange, 404, "No players online");
                return;
            }

            String body = readBody(exchange);
            String message = extractJsonString(body, "message");
            if (message == null || message.isEmpty()) {
                ApiServer.sendError(exchange, 400, "Missing 'message' field");
                return;
            }

            // Send as player chat
            server.getCommandManager().executeCommand(player, "/me " + message);
            // Also add to chat for other players
            server.getConfigurationManager().sendChatMsg(
                new ChatComponentText("<" + player.getCommandSenderName() + "> " + message)
            );
            // Do NOT record in chat history — prevents echo loop where
            // the cron reads its own messages and responds to them.

            ApiServer.sendJson(exchange, 200, "{\"sent\":true,\"message\":\"" + ApiServer.escapeJson(message) + "\"}");
        }
    }

    // --- /action/click ---
    // POST body: {"target": "block", "x": 10, "y": 64, "z": 10, "button": "right"}
    // Simulates a right-click on a block. "target" can be "block" or "entity".

    public static class ClickHandler implements HttpHandler {
        private final boolean enabled;

        public ClickHandler(boolean enabled) { this.enabled = enabled; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                ApiServer.sendError(exchange, 405, "Method not allowed");
                return;
            }
            if (!enabled) {
                ApiServer.sendError(exchange, 403, "Actions disabled in config");
                return;
            }
            if (!ApiServer.checkAuth(exchange, ScribeMod.authToken)) {
                ApiServer.sendError(exchange, 401, "Unauthorized");
                return;
            }

            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) {
                ApiServer.sendError(exchange, 503, "Server not running");
                return;
            }

            EntityPlayerMP player = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
            if (player == null) {
                ApiServer.sendError(exchange, 404, "No players online");
                return;
            }

            String body = readBody(exchange);
            int x = extractJsonInt(body, "x", (int) player.posX);
            int y = extractJsonInt(body, "y", (int) player.posY);
            int z = extractJsonInt(body, "z", (int) player.posZ);
            String button = extractJsonString(body, "button");
            if (button == null) button = "right";

            // Simulate a right-click on the block at x,y,z
            // This uses the player's interaction manager
            if ("right".equals(button)) {
                player.theItemInWorldManager.activateBlockOrUseItem(
                    player, player.worldObj, player.getHeldItem(), x, y, z,
                    1,  // side (top)
                    0.5f, 0.5f, 0.5f  // hit vector
                );
            } else {
                // Left click = start breaking
                player.theItemInWorldManager.onBlockClicked(x, y, z, 1);
            }

            ApiServer.sendJson(exchange, 200,
                "{\"clicked\":true,\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"button\":\"" + button + "\"}");
        }
    }

    // --- /action/move ---
    // POST body: {"x": 100.5, "y": 64.0, "z": 200.3}
    // Teleports the player to the given coordinates.
    // NOTE: This is a teleport, not pathfinding. For pathfinding, pair with Baritone.

    public static class MoveHandler implements HttpHandler {
        private final boolean enabled;

        public MoveHandler(boolean enabled) { this.enabled = enabled; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                ApiServer.sendError(exchange, 405, "Method not allowed");
                return;
            }
            if (!enabled) {
                ApiServer.sendError(exchange, 403, "Actions disabled in config");
                return;
            }
            if (!ApiServer.checkAuth(exchange, ScribeMod.authToken)) {
                ApiServer.sendError(exchange, 401, "Unauthorized");
                return;
            }

            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) {
                ApiServer.sendError(exchange, 503, "Server not running");
                return;
            }

            EntityPlayerMP player = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
            if (player == null) {
                ApiServer.sendError(exchange, 404, "No players online");
                return;
            }

            String body = readBody(exchange);
            double x = extractJsonDouble(body, "x", player.posX);
            double y = extractJsonDouble(body, "y", player.posY);
            double z = extractJsonDouble(body, "z", player.posZ);

            player.setPositionAndUpdate(x, y, z);

            ApiServer.sendJson(exchange, 200,
                "{\"moved\":true,\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}");
        }
    }

    // --- JSON helpers (no external libs — manual parsing) ---

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int n;
        while ((n = is.read(data)) != -1) buf.write(data, 0, n);
        return buf.toString("UTF-8");
    }

    private static String extractJsonString(String json, String key) {
        // Minimal JSON string extractor: "key":"value" or "key": "value"
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        // Skip optional whitespace after colon
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        start++; // skip opening quote
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && (end == start || json.charAt(end - 1) != '\\')) {
                break;
            }
            end++;
        }
        if (end >= json.length()) return null;
        return unescapeJsonString(json.substring(start, end));
    }

    private static String unescapeJsonString(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case '/': sb.append('/'); i += 2; continue;
                    case 'b': sb.append('\b'); i += 2; continue;
                    case 'f': sb.append('\f'); i += 2; continue;
                    case 'n': sb.append('\n'); i += 2; continue;
                    case 'r': sb.append('\r'); i += 2; continue;
                    case 't': sb.append('\t'); i += 2; continue;
                    case 'u':
                        if (i + 5 < s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            try {
                                int code = Integer.parseInt(hex, 16);
                                sb.append((char) code);
                                i += 6;
                                continue;
                            } catch (NumberFormatException e) {}
                        }
                        sb.append(c);
                        i++;
                        break;
                    default:
                        sb.append(next);
                        i += 2;
                        continue;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int extractJsonInt(String json, String key, int defaultVal) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return defaultVal;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return defaultVal;
        try { return Integer.parseInt(json.substring(start, end)); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static double extractJsonDouble(String json, String key, double defaultVal) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return defaultVal;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        if (end == start) return defaultVal;
        try { return Double.parseDouble(json.substring(start, end)); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
