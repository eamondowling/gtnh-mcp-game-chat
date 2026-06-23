package com.hermes.hermeslink;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.hermes.hermeslink.chat.ChatListener;

@Mod(modid = HermesLink.MODID, version = HermesLink.VERSION, name = "Hermes Link")
public class HermesLink {
    public static final String MODID = "hermes-link";
    public static final String VERSION = "1.1.0";

    public static final Logger LOG = LogManager.getLogger("HermesLink");

    public static int port = 25566;
    public static boolean allowActions = true;
    public static String authToken = "";

    private ApiServer server;
    private ChatListener chatListener;
    private GuideBook guideBook;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        port = config.getInt("port", "server", 25566, 1024, 65535,
                "Port for the Hermes Link HTTP API");
        allowActions = config.getBoolean("allowActions", "server", true,
                "Enable write endpoints (chat, click, move, book)");
        authToken = config.getString("authToken", "server", "",
                "Optional bearer token for API auth. Empty = no auth.");
        config.save();
        LOG.info("Hermes Link config: port={}, allowActions={}, auth={}",
                port, allowActions, authToken.isEmpty() ? "none" : "set");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        chatListener = new ChatListener();
        MinecraftForge.EVENT_BUS.register(chatListener);

        guideBook = new GuideBook();
        MinecraftForge.EVENT_BUS.register(guideBook);

        LOG.info("Hermes Link initialized — chat listener + guide book registered");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        server = new ApiServer(port, authToken, allowActions);
        server.start();
        LOG.info("Hermes Link API listening on http://localhost:{}", port);

        // Register /hermes guide command
        event.registerServerCommand(new CommandBase() {
            @Override
            public String getCommandName() {
                return "hermes";
            }

            @Override
            public String getCommandUsage(ICommandSender sender) {
                return "/hermes guide — get a new Hermes Agent Guide book";
            }

            @Override
            public void processCommand(ICommandSender sender, String[] args) {
                if (args.length > 0 && "guide".equals(args[0])) {
                    if (sender instanceof EntityPlayerMP) {
                        GuideBook.giveToPlayer((EntityPlayerMP) sender);
                    } else {
                        // Console sender — just log
                        LOG.info("Guide book requested from console (no player to give to)");
                    }
                } else {
                    sender.addChatMessage(
                        new net.minecraft.util.ChatComponentText(
                            "§e[Hermes]§r Use §n/hermes guide§r to get the instruction book."
                        )
                    );
                }
            }

            @Override
            public int getRequiredPermissionLevel() {
                return 0; // anyone can use it
            }
        });
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (server != null) {
            server.stop();
            LOG.info("Hermes Link API stopped");
        }
    }
}
