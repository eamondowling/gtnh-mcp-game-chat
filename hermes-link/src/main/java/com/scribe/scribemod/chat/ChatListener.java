package com.scribe.scribemod.chat;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.ServerChatEvent;
import com.scribe.scribemod.ScribeMod;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Captures all chat messages on the server and stores them for the API.
 * Thread-safe — chat events come from the server thread, API reads from HTTP threads.
 *
 * Supports @scribe prefix for direct agent messages (not broadcast to other players).
 */
public class ChatListener {

    private static final int MAX_HISTORY = 200;
    private static final ConcurrentLinkedQueue<ChatMessage> history =
        new ConcurrentLinkedQueue<ChatMessage>();
    private static final ConcurrentLinkedQueue<ChatMessage> directHistory =
        new ConcurrentLinkedQueue<ChatMessage>();

    private static final List<Object> pollWaiters = new ArrayList<>();
    private static final Object pollLock = new Object();
    private static final Object directPollLock = new Object();

    public static final String DIRECT_PREFIX = "@scribe";

    @SubscribeEvent
    public void onChatMessage(ServerChatEvent event) {
        String msg = event.message.trim();

        // Check for @scribe prefix (case insensitive) — always processed
        if (msg.toLowerCase().startsWith(DIRECT_PREFIX.toLowerCase())) {
            String content = msg.substring(DIRECT_PREFIX.length()).trim();
            if (content.startsWith(":") || content.startsWith(" ")) {
                content = content.substring(1).trim();
            }
            recordDirectMessage(event.username, content);
            event.setCanceled(true);
            return;
        }

        // Public chat — only record if talk mode is enabled
        if (ScribeMod.talkEnabled) {
            recordMessage(event.username, event.message);
        }
    }

    /**
     * Record a message directly — used by the API's chat sender so outgoing
     * messages also appear in history. Thread-safe.
     */
    public static void recordMessage(String username, String message) {
        ChatMessage msg = new ChatMessage(
            username,
            message,
            System.currentTimeMillis()
        );

        history.add(msg);
        while (history.size() > MAX_HISTORY) {
            history.poll();
        }

        synchronized (pollLock) {
            pollLock.notifyAll();
        }
    }

    /**
     * Record a direct message (via @scribe prefix) — not broadcast to other players.
     * Stored separately from public chat history.
     */
    public static void recordDirectMessage(String username, String message) {
        ChatMessage msg = new ChatMessage(
            username,
            message,
            System.currentTimeMillis(),
            true  // isDirect
        );

        directHistory.add(msg);
        while (directHistory.size() > MAX_HISTORY) {
            directHistory.poll();
        }

        // Also add to regular history so API clients polling regular chat see it
        history.add(msg);
        while (history.size() > MAX_HISTORY) {
            history.poll();
        }

        synchronized (directPollLock) {
            directPollLock.notifyAll();
        }
        synchronized (pollLock) {
            pollLock.notifyAll();
        }
    }

    public static List<ChatMessage> getDirectHistory() {
        return new ArrayList<>(directHistory);
    }

    public static List<ChatMessage> getDirectHistorySince(long sinceTimestamp) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : directHistory) {
            if (msg.timestamp > sinceTimestamp) {
                result.add(msg);
            }
        }
        return result;
    }

    public static List<ChatMessage> pollDirectMessages(long sinceTimestamp, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        synchronized (directPollLock) {
            while (true) {
                List<ChatMessage> newMsgs = getDirectHistorySince(sinceTimestamp);
                if (!newMsgs.isEmpty()) {
                    return newMsgs;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return newMsgs;
                }
                try {
                    directPollLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return getDirectHistorySince(sinceTimestamp);
                }
            }
        }
    }

    public static List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public static List<ChatMessage> getHistorySince(long sinceTimestamp) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : history) {
            if (msg.timestamp > sinceTimestamp) {
                result.add(msg);
            }
        }
        return result;
    }

    public static List<ChatMessage> pollNewMessages(long sinceTimestamp, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        synchronized (pollLock) {
            while (true) {
                List<ChatMessage> newMsgs = getHistorySince(sinceTimestamp);
                if (!newMsgs.isEmpty()) {
                    return newMsgs;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return newMsgs;
                }
                try {
                    pollLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return getHistorySince(sinceTimestamp);
                }
            }
        }
    }

    public static class ChatMessage {
        public final String username;
        public final String message;
        public final long timestamp;
        public final boolean isDirect;

        public ChatMessage(String username, String message, long timestamp) {
            this(username, message, timestamp, false);
        }

        public ChatMessage(String username, String message, long timestamp, boolean isDirect) {
            this.username = username;
            this.message = message;
            this.timestamp = timestamp;
            this.isDirect = isDirect;
        }

        public String toJson() {
            return "{\"username\":\"" + escapeJson(username) + "\"," +
                   "\"message\":\"" + escapeJson(message) + "\"," +
                   "\"timestamp\":" + timestamp + "," +
                   "\"isDirect\":" + isDirect + "}";
        }

        private static String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
