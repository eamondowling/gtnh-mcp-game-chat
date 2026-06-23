package com.scribe.scribemod.chat;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.ServerChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Captures all chat messages on the server and stores them for the API.
 * Thread-safe — chat events come from the server thread, API reads from HTTP threads.
 */
public class ChatListener {

    private static final int MAX_HISTORY = 200;
    private static final ConcurrentLinkedQueue<ChatMessage> history =
        new ConcurrentLinkedQueue<ChatMessage>();

    private static final List<Object> pollWaiters = new ArrayList<>();
    private static final Object pollLock = new Object();

    @SubscribeEvent
    public void onChatMessage(ServerChatEvent event) {
        recordMessage(event.username, event.message);
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

        public ChatMessage(String username, String message, long timestamp) {
            this.username = username;
            this.message = message;
            this.timestamp = timestamp;
        }

        public String toJson() {
            return "{\"username\":\"" + escapeJson(username) + "\"," +
                   "\"message\":\"" + escapeJson(message) + "\"," +
                   "\"timestamp\":" + timestamp + "}";
        }

        private static String escapeJson(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
