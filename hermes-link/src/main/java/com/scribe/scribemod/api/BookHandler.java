package com.scribe.scribemod.api;

import com.scribe.scribemod.ApiServer;
import com.scribe.scribemod.ScribeMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * POST /action/book
 * Body: {"title": "Ore Survey", "author": "Hermes", "pages": ["page1", "page2", ...]}
 *
 * Creates a written book with the given pages and gives it to the player.
 * Max 50 pages, each page max 256 chars (Minecraft's limit).
 * If the player's inventory is full, the book drops at their feet.
 */
public class BookHandler implements HttpHandler {
    private final boolean enabled;

    public BookHandler(boolean enabled) { this.enabled = enabled; }

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

        String title = extractJsonString(body, "title");
        if (title == null || title.isEmpty()) title = "Agent Report";

        String author = extractJsonString(body, "author");
        if (author == null || author.isEmpty()) author = "Hermes";

        // Extract pages array — manual parsing since we don't have a JSON library
        List<String> pages = extractJsonStringArray(body, "pages");
        if (pages.isEmpty()) {
            ApiServer.sendError(exchange, 400, "Missing 'pages' array");
            return;
        }

        // Enforce Minecraft limits
        if (pages.size() > 50) {
            pages = pages.subList(0, 50);
        }
        List<String> trimmed = new ArrayList<>();
        for (String page : pages) {
            if (page.length() > 256) {
                trimmed.add(page.substring(0, 256));
            } else {
                trimmed.add(page);
            }
        }

        // Create the written book
        ItemStack book = new ItemStack(Items.written_book);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("title", title);
        tag.setString("author", author);

        NBTTagList pageList = new NBTTagList();
        for (String page : trimmed) {
            pageList.appendTag(new NBTTagString(page));
        }
        tag.setTag("pages", pageList);
        book.setTagCompound(tag);

        // Give to player — try inventory first, drop at feet if full
        if (!player.inventory.addItemStackToInventory(book)) {
            player.dropPlayerItemWithRandomChoice(book, false);
        }

        ApiServer.sendJson(exchange, 200,
            "{\"written\":true,\"title\":\"" + ApiServer.escapeJson(title) + "\"," +
            "\"pages\":" + trimmed.size() + "}");
    }

    // --- Manual JSON array-of-strings extractor ---

    private static List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return result;
        start += search.length();

        // Skip whitespace
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
        if (start >= json.length() || json.charAt(start) != '[') return result;
        start++; // skip '['

        // Parse each string element
        while (start < json.length()) {
            // Skip whitespace and commas
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t' || json.charAt(start) == ',')) start++;
            if (start >= json.length()) break;
            if (json.charAt(start) == ']') break;
            if (json.charAt(start) != '"') break;

            start++; // skip opening quote
            int end = start;
            while (end < json.length()) {
                if (json.charAt(end) == '"' && (end == start || json.charAt(end - 1) != '\\')) {
                    break;
                }
                end++;
            }
            if (end >= json.length()) break;
            result.add(json.substring(start, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n"));
            start = end + 1; // skip closing quote
        }

        return result;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int n;
        while ((n = is.read(data)) != -1) buf.write(data, 0, n);
        return buf.toString("UTF-8");
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        start++;
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }
}
