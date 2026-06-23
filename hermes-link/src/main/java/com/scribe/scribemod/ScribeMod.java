package com.scribe.scribemod;

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

import com.scribe.scribemod.chat.ChatListener;

@Mod(modid = ScribeMod.MODID, version = ScribeMod.VERSION, name = "Scribe")
public class ScribeMod {
    public static final String MODID = "scribe";
    public static final String VERSION = "1.1.0";

    public static final Logger LOG = LogManager.getLogger("ScribeMod");

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
                "Port for the Scribe HTTP API");
        allowActions = config.getBoolean("allowActions", "server", true,
                "Enable write endpoints (chat, click, move, book)");
        authToken = config.getString("authToken", "server", "",
                "Optional bearer token for API auth. Empty = no auth.");
        config.save();
        LOG.info("Scribe config: port={}, allowActions={}, auth={}",
                port, allowActions, authToken.isEmpty() ? "none" : "set");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        chatListener = new ChatListener();
        MinecraftForge.EVENT_BUS.register(chatListener);

        guideBook = new GuideBook();
        MinecraftForge.EVENT_BUS.register(guideBook);

        LOG.info("Scribe initialized — chat listener + guide book registered");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        server = new ApiServer(port, authToken, allowActions);
        server.start();
        LOG.info("Scribe API listening on http://localhost:{}", port);

        // Register /scribe guide command
        event.registerServerCommand(new CommandBase() {
            @Override
            public String getCommandName() {
                return "scribe";
            }

            @Override
            public String getCommandUsage(ICommandSender sender) {
                return "/scribe guide — get a new Scribe Agent Guide book";
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
                            "§e[Scribe]§r Use §n/scribe guide§r to get the instruction book."
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
            LOG.info("Scribe API stopped");
        }
    }
}
