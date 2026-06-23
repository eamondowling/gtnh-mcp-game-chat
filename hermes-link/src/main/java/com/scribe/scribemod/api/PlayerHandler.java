package com.scribe.scribemod.api;

import com.scribe.scribemod.ApiServer;
import com.scribe.scribemod.ScribeMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class PlayerHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            ApiServer.sendError(exchange, 405, "Method not allowed");
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

        // Return all online players
        StringBuilder sb = new StringBuilder("{\"players\":[");
        boolean first = true;
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) obj;
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"name\":\"").append(ApiServer.escapeJson(player.getCommandSenderName())).append("\",");
            sb.append("\"uuid\":\"").append(player.getUniqueID().toString()).append("\",");
            sb.append("\"dimension\":").append(player.dimension).append(",");
            sb.append("\"x\":").append(player.posX).append(",");
            sb.append("\"y\":").append(player.posY).append(",");
            sb.append("\"z\":").append(player.posZ).append(",");
            sb.append("\"health\":").append(player.getHealth()).append(",");
            sb.append("\"maxHealth\":").append(player.getMaxHealth()).append(",");
            sb.append("\"foodLevel\":").append(player.getFoodStats().getFoodLevel()).append(",");
            sb.append("\"xpLevel\":").append(player.experienceLevel).append(",");
            sb.append("\"gamemode\":").append(player.theItemInWorldManager.getGameType().getID());
            sb.append("}");
        }
        sb.append("]}");
        ApiServer.sendJson(exchange, 200, sb.toString());
    }

    // --- /player/inventory ---

    public static class InventoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                ApiServer.sendError(exchange, 405, "Method not allowed");
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

            // Get player from query param, or first online player
            String playerName = getQueryParam(exchange, "player");
            EntityPlayerMP player = null;
            if (playerName != null) {
                player = server.getConfigurationManager().func_152612_a(playerName);
            }
            if (player == null && !server.getConfigurationManager().playerEntityList.isEmpty()) {
                player = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
            }
            if (player == null) {
                ApiServer.sendError(exchange, 404, "No players online");
                return;
            }

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"player\":\"").append(ApiServer.escapeJson(player.getCommandSenderName())).append("\",");
            sb.append("\"items\":[");

            boolean first = true;
            for (int i = 0; i < player.inventory.mainInventory.length; i++) {
                ItemStack stack = player.inventory.mainInventory[i];
                if (stack == null) continue;
                if (!first) sb.append(",");
                first = false;
                appendItemJson(sb, i, stack);
            }

            // Armor
            for (int i = 0; i < player.inventory.armorInventory.length; i++) {
                ItemStack stack = player.inventory.armorInventory[i];
                if (stack == null) continue;
                if (!first) sb.append(",");
                first = false;
                appendItemJson(sb, 100 + i, stack);
            }

            sb.append("]}");
            ApiServer.sendJson(exchange, 200, sb.toString());
        }

        private void appendItemJson(StringBuilder sb, int slot, ItemStack stack) {
            sb.append("{");
            sb.append("\"slot\":").append(slot).append(",");
            sb.append("\"id\":\"").append(net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem())).append("\",");
            sb.append("\"damage\":").append(stack.getItemDamage()).append(",");
            sb.append("\"count\":").append(stack.stackSize).append(",");
            sb.append("\"maxStack\":").append(stack.getMaxStackSize()).append(",");
            sb.append("\"displayName\":\"").append(ApiServer.escapeJson(stack.getDisplayName())).append("\"");
            if (stack.hasTagCompound()) {
                sb.append(",\"nbt\":").append(nbtToJson(stack.getTagCompound()));
            }
            sb.append("}");
        }

        private String nbtToJson(NBTTagCompound tag) {
            // Simplified NBT → JSON. Full fidelity would need a recursive walker.
            // For now, return a summary of key types.
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"_keys\":[");
            boolean first = true;
            for (Object k : tag.func_150296_c()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(k.toString()).append("\"");
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
        }
    }

    // --- /player/nearby ---

    public static class NearbyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                ApiServer.sendError(exchange, 405, "Method not allowed");
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

            int radius = 10;
            String r = getQueryParam(exchange, "radius");
            if (r != null) {
                try { radius = Integer.parseInt(r); } catch (NumberFormatException ignored) {}
            }

            World world = player.worldObj;
            AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
                player.posX - radius, player.posY - radius, player.posZ - radius,
                player.posX + radius, player.posY + radius, player.posZ + radius
            );

            StringBuilder sb = new StringBuilder("{\"entities\":[");
            boolean first = true;
            for (Object obj : world.getEntitiesWithinAABB(net.minecraft.entity.Entity.class, box)) {
                net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) obj;
                if (entity == player) continue; // skip self
                if (!first) sb.append(",");
                first = false;
                sb.append("{");
                sb.append("\"type\":\"").append(entity.getClass().getSimpleName()).append("\",");
                sb.append("\"name\":\"").append(ApiServer.escapeJson(entity.getCommandSenderName())).append("\",");
                sb.append("\"x\":").append(entity.posX).append(",");
                sb.append("\"y\":").append(entity.posY).append(",");
                sb.append("\"z\":").append(entity.posZ).append(",");
                sb.append("\"distance\":").append(player.getDistanceToEntity(entity));
                sb.append("}");
            }
            sb.append("]}");
            ApiServer.sendJson(exchange, 200, sb.toString());
        }
    }

    // --- Query param helper ---

    static String getQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv[1]);
            }
        }
        return null;
    }
}
