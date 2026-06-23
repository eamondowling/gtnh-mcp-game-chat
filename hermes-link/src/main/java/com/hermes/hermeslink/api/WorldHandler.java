package com.hermes.hermeslink.api;

import com.hermes.hermeslink.ApiServer;
import com.hermes.hermeslink.HermesLink;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.io.IOException;

public class WorldHandler {

    // --- /world/block?x=0&y=64&z=0 ---

    public static class BlockHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                ApiServer.sendError(exchange, 405, "Method not allowed");
                return;
            }
            if (!ApiServer.checkAuth(exchange, HermesLink.authToken)) {
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

            int x = intParam(exchange, "x", (int) player.posX);
            int y = intParam(exchange, "y", (int) player.posY);
            int z = intParam(exchange, "z", (int) player.posZ);

            World world = player.worldObj;
            Block block = world.getBlock(x, y, z);
            int meta = world.getBlockMetadata(x, y, z);

            String json = "{"
                + "\"x\":" + x + ","
                + "\"y\":" + y + ","
                + "\"z\":" + z + ","
                + "\"block\":\"" + Block.blockRegistry.getNameForObject(block) + "\","
                + "\"meta\":" + meta + ","
                + "\"light\":" + world.getBlockLightValue(x, y, z) + ","
                + "\"solid\":" + block.getMaterial().isSolid()
                + "}";
            ApiServer.sendJson(exchange, 200, json);
        }
    }

    // --- /world/blocks?x=0&y=64&z=0&radius=5 ---

    public static class BlocksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                ApiServer.sendError(exchange, 405, "Method not allowed");
                return;
            }
            if (!ApiServer.checkAuth(exchange, HermesLink.authToken)) {
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

            int cx = intParam(exchange, "x", (int) player.posX);
            int cy = intParam(exchange, "y", (int) player.posY);
            int cz = intParam(exchange, "z", (int) player.posZ);
            int radius = intParam(exchange, "radius", 5);
            if (radius > 32) radius = 32; // safety cap

            World world = player.worldObj;
            StringBuilder sb = new StringBuilder("{\"center\":{\"x\":").append(cx)
                .append(",\"y\":").append(cy).append(",\"z\":").append(cz)
                .append("},\"radius\":").append(radius).append(",\"blocks\":[");

            boolean first = true;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int bx = cx + dx, by = cy + dy, bz = cz + dz;
                        Block block = world.getBlock(bx, by, bz);
                        if (block.isAir(world, bx, by, bz)) continue;
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{\"x\":").append(bx)
                          .append(",\"y\":").append(by)
                          .append(",\"z\":").append(bz)
                          .append(",\"block\":\"")
                          .append(Block.blockRegistry.getNameForObject(block))
                          .append("\",\"meta\":").append(world.getBlockMetadata(bx, by, bz))
                          .append("}");
                    }
                }
            }
            sb.append("]}");
            ApiServer.sendJson(exchange, 200, sb.toString());
        }
    }

    private static int intParam(HttpExchange exchange, String key, int defaultVal) {
        String val = PlayerHandler.getQueryParam(exchange, key);
        if (val == null) return defaultVal;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultVal; }
    }
}
