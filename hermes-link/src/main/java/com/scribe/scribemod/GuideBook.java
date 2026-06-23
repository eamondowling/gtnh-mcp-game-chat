package com.scribe.scribemod;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ChatComponentText;

/**
 * Auto-gives a guide book to players when they join a world.
 * Also provides a static method for the /scribe guide command.
 */
public class GuideBook {

    private static final String[] PAGES = {
        // Page 1 — Intro
        "§lScribe Agent Guide§r\n\n" +
        "Your AI companion for GTNH.\n" +
        "Type commands in chat and\n" +
        "I'll help you play.\n\n" +
        "§nHow to talk to me:§r\n" +
        "Just type in chat! I read\n" +
        "every message and respond\n" +
        "within a minute.",

        // Page 2 — Commands
        "§lCommands I Understand§r\n\n" +
        "§nAsk about your world:§r\n" +
        "\"what's around me?\"\n" +
        "\"scan for ores\"\n" +
        "\"what's in my inventory?\"\n" +
        "\"where am I?\"\n" +
        "\"how's my health?\"\n\n" +
        "I'll write a book with\n" +
        "full details.",

        // Page 3 — Actions
        "§lActions I Can Do§r\n\n" +
        "§nMovement:§r\n" +
        "\"teleport to 100, 64, 200\"\n" +
        "\"go to my base\"\n" +
        "(tell me coordinates)\n\n" +
        "§nInteraction:§r\n" +
        "\"open the chest at x,y,z\"\n" +
        "\"mine the block at x,y,z\"\n" +
        "\"check that machine\"\n\n" +
        "I'll confirm in chat.",

        // Page 4 — Scanning
        "§lScanning & Surveys§r\n\n" +
        "\"scan for ores within\n" +
        " 20 blocks\"\n" +
        "\"survey this area\"\n" +
        "\"what ores are nearby?\"\n\n" +
        "I scan all non-air blocks\n" +
        "around you and write an\n" +
        "Ore Survey book with\n" +
        "counts and locations.",

        // Page 5 — Inventory
        "§lInventory & Status§r\n\n" +
        "\"what am I carrying?\"\n" +
        "\"do I have any diamonds?\"\n" +
        "\"check my tools\"\n" +
        "\"how much food do I have?\"\n\n" +
        "I read your full inventory\n" +
        "— every item, count,\n" +
        "damage, and enchantment.\n" +
        "Results go into a book.",

        // Page 6 — Books
        "§lBooks & Reports§r\n\n" +
        "All detailed answers come\n" +
        "as written books in your\n" +
        "inventory. Chat is for\n" +
        "short replies only.\n\n" +
        "Look for books titled:\n" +
        "- Ore Survey\n" +
        "- Inventory Report\n" +
        "- Quest Summary\n" +
        "- Base Layout\n\n" +
        "Right-click to read them!",

        // Page 7 — Tips
        "§lTips§r\n\n" +
        "§nBe specific:§r\n" +
        "\"scan for ores r=30\"\n" +
        "is better than \"scan\"\n\n" +
        "§nCoordinates help:§r\n" +
        "Press F3 to see x,y,z.\n\n" +
        "§nI respond within 60s:§r\n" +
        "Not instant — I think\n" +
        "before I act!\n\n" +
        "§nDangerous requests:§r\n" +
        "I'll warn you if something\n" +
        "seems risky.",

        // Page 8 — Examples
        "§lExamples§r\n\n" +
        "You: \"scan for ores r=20\"\n" +
        "Me: \"Scanning...\"\n" +
        "→ Book: Ore Survey\n\n" +
        "You: \"do I have copper?\"\n" +
        "Me: \"Checking...\"\n" +
        "→ Book: Inventory Report\n\n" +
        "You: \"teleport to 0,70,0\"\n" +
        "Me: \"Done. At spawn.\"\n\n" +
        "You: \"open chest at\n" +
        " -474,64,48\"\n" +
        "Me: \"Opened.\"",

        // Page 9 — About
        "§lAbout Scribe§r\n\n" +
        "I'm an AI agent connected\n" +
        "through the Scribe mod by\n" +
        "Nous Research.\n\n" +
        "I connect to Minecraft via\n" +
        "the Scribe mod and\n" +
        "MCP protocol.\n\n" +
        "I can see your world, read\n" +
        "your inventory, scan blocks,\n" +
        "and interact with the game\n" +
        "— all through chat.",

        // Page 10 — Setup for friends
        "§lSetup for Friends§r\n\n" +
        "Want your own agent?\n\n" +
        "github.com/eamondowling/\n" +
        "gtnh-mcp-game-chat\n\n" +
        "Works with Hermes, Claude,\n" +
        "Codex, OpenClaw, Pi-agent,\n" +
        "and any MCP-compatible AI.\n\n" +
        "Cross-platform: Windows,\n" +
        "macOS, Linux.\n\n" +
        "§n/ hermes guide§r to\n" +
        "regenerate this book."
    };

    /**
     * Create the guide book ItemStack. Call this from anywhere.
     */
    public static ItemStack create() {
        ItemStack book = new ItemStack(Items.written_book);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("title", "Scribe Agent Guide");
        tag.setString("author", "Scribe");

        NBTTagList pageList = new NBTTagList();
        for (String page : PAGES) {
            pageList.appendTag(new NBTTagString(page));
        }
        tag.setTag("pages", pageList);
        book.setTagCompound(tag);
        return book;
    }

    /**
     * Give the guide book to a player. If inventory is full, drops at feet.
     */
    public static void giveToPlayer(EntityPlayer player) {
        ItemStack book = create();
        if (!player.inventory.addItemStackToInventory(book)) {
            player.dropPlayerItemWithRandomChoice(book, false);
        }
        player.addChatMessage(new ChatComponentText(
            "§a[ Scribe]§r Guide book added to your inventory. Right-click to read!"
        ));
    }

    // --- Auto-give on world join ---

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        giveToPlayer(event.player);
    }
}
