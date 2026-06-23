package com.hermes.hermeslink;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = HermesLink.MODID, version = HermesLink.VERSION, name = "Hermes Link")
public class HermesLink {
    public static final String MODID = "hermes-link";
    public static final String VERSION = "1.0.0";

    public static final Logger LOG = LogManager.getLogger("HermesLink");

    public static int port = 25566;
    public static boolean allowActions = true;
    public static String authToken = "";

    private ApiServer server;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        port = config.getInt("port", "server", 25566, 1024, 65535,
                "Port for the Hermes Link HTTP API");
        allowActions = config.getBoolean("allowActions", "server", true,
                "Enable write endpoints (chat, click, move)");
        authToken = config.getString("authToken", "server", "",
                "Optional bearer token for API auth. Empty = no auth.");
        config.save();
        LOG.info("Hermes Link config: port={}, allowActions={}, auth={}",
                port, allowActions, authToken.isEmpty() ? "none" : "set");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOG.info("Hermes Link initialized");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        server = new ApiServer(port, authToken, allowActions);
        server.start();
        LOG.info("Hermes Link API listening on http://localhost:{}", port);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (server != null) {
            server.stop();
            LOG.info("Hermes Link API stopped");
        }
    }
}
