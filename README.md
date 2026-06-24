# GTNH MCP Game Chat

An MCP (Model Context Protocol) server that bridges AI agents into Minecraft GTNH chat via the **Scribe** mod. The agent appears as a player — it reads chat messages, responds, writes books, and can control the game through a local HTTP API.

**Cross-platform** — the MCP server is pure Python (Windows, macOS, Linux). The Scribe mod is pure Java (anywhere Forge runs). Works with any MCP-compatible agent: Claude, Codex, OpenClaw, Pi-agent, etc.

## Architecture

```
┌──────────────────────┐     MCP (stdio)      ┌──────────────────────┐
│   AI Agent            │ ◄─────────────────► │  gtnh_chat_mcp.py    │
│   (Claude, Codex,     │                      │  (Python MCP server) │
│    OpenClaw, Pi-agent,│                      └──────────┬───────────┘
│    etc.)              │                                 │
└──────────────────────┘                          HTTP (localhost:25566)
                                                         │
                                              ┌──────────▼───────────┐
                                              │  Scribe mod          │
                                              │  (Forge 1.7.10)      │
                                              │                      │
                                              │  Chat capture        │
                                              │  Player state        │
                                              │  Block scanning      │
                                              │  Written books       │
                                              │  Actions (chat,      │
                                              │   click, teleport)   │
                                              └──────────┬───────────┘
                                                         │
                                              ┌──────────▼───────────┐
                                              │  Minecraft 1.7.10    │
                                              │  + GTNH + Forge      │
                                              └──────────────────────┘
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `gtnh_ping` | Check connection to Minecraft |
| `gtnh_chat_read` | Read new chat messages since last poll |
| `gtnh_chat_send` | Send a chat message (agent speaks as player) |
| `gtnh_player_status` | Player position, health, food, XP, dimension |
| `gtnh_inventory` | Full inventory with item names, counts, slots |
| `gtnh_scan_blocks` | Survey non-air blocks around player |
| `gtnh_teleport` | Teleport to exact coordinates |
| `gtnh_click_block` | Right/left click a block |
| `gtnh_write_book` | Create a written book (max 50 pages, 256 chars each) |

## The Book Pattern

Minecraft chat is limited to ~100 characters. Scribe uses **written books** for verbose output:

- **Chat**: brief acknowledgments ("On it.", "Report ready — check your inventory.")
- **Books**: full reports — ore surveys, inventory dumps, quest summaries, base layouts

Books appear in your inventory. Right-click to read. If inventory is full, they drop at your feet.

## In-Game Guide Book

When you install the Scribe mod and join a world, a **"Scribe Agent Guide"** book appears in your inventory automatically. It covers commands, actions, scanning, inventory, books, tips, and examples.

If you lose it, type `/scribe guide` in chat to get a fresh copy. Anyone can use this command.

## Quick Start

### 1. Build and install the mod

```bash
cd scribe-mod
./gradlew build
# Copy build/libs/scribe-1.1.0.jar into your GTNH instance's mods/ folder
```

Requires JDK 17+ and Gradle 7.6+ (uses RetroFuturaGradle for 1.7.10 targeting).

### 2. Install the MCP Python package

```bash
pip install mcp
```

### 3. Configure your agent

**Hermes Agent** (`~/.hermes/config.yaml`):
```yaml
mcp_servers:
  gtnh:
    command: "python"
    args:
      - "/path/to/gtnh-mcp-game-chat/mcp-server/gtnh_chat_mcp.py"
    env:
      GTNH_MCP_BASE_URL: "http://127.0.0.1:25566"
    timeout: 30
```

**Claude Desktop** (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "gtnh": {
      "command": "python",
      "args": ["/path/to/gtnh_chat_mcp.py"],
      "env": {
        "GTNH_MCP_BASE_URL": "http://127.0.0.1:25566"
      }
    }
  }
}
```

**Codex / OpenClaw / Pi-agent** — same pattern: `command: python`, `args: [script path]`, `env: GTNH_MCP_BASE_URL`.

### 4. Launch Minecraft

Start GTNH through Prism Launcher. Load a world. The Scribe API starts on `localhost:25566`. You'll get the guide book automatically.

### 5. Restart your agent

The MCP server connects on startup. Tools appear with the `gtnh_` prefix.

## How the agent uses it

```
Agent loop:
  1. gtnh_chat_read    → see what players are saying
  2. gtnh_chat_send    → brief ack in chat
  3. gtnh_player_status → check position, health
  4. gtnh_inventory    → check what you're carrying
  5. gtnh_scan_blocks  → survey the area
  6. gtnh_write_book   → full report as a readable book
  7. gtnh_teleport     → move to a location
  8. gtnh_click_block  → interact with a machine or chest
```

## Project Structure

```
gtnh-mcp-game-chat/
├── README.md
├── scribe-mod/                     # Forge 1.7.10 mod
│   ├── build.gradle
│   ├── gradlew / gradlew.bat
│   └── src/main/java/com/scribe/scribemod/
│       ├── ScribeMod.java          # @Mod entry point + /scribe guide command
│       ├── ApiServer.java          # HTTP server + chat endpoints
│       ├── GuideBook.java          # Auto-given instruction book
│       ├── chat/
│       │   └── ChatListener.java   # Forge event bus chat capture
│       └── api/
│           ├── PlayerHandler.java  # /player, /inventory, /nearby
│           ├── WorldHandler.java   # /world/block, /world/blocks
│           ├── ActionHandler.java  # /action/chat, /click, /move
│           └── BookHandler.java    # /action/book — written books
└── mcp-server/
    └── gtnh_chat_mcp.py            # MCP stdio server (9 tools)
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `GTNH_MCP_BASE_URL` | `http://127.0.0.1:25566` | Scribe mod API URL |
| `GTNH_MCP_TOKEN` | `""` | Bearer token (if auth is enabled in mod config) |

## Cross-Platform Notes

- **Windows**: Prism instances at `%APPDATA%\PrismLauncher\instances\`
- **macOS**: Prism instances at `~/Library/Application Support/PrismLauncher/instances/`
- **Linux**: Prism instances at `~/.local/share/PrismLauncher/instances/`
- The mod jar is identical on all platforms
- The MCP server script is identical on all platforms

## Requirements

- **Minecraft**: GTNH 2.8+ (1.7.10 Forge) via Prism Launcher
- **Java**: JDK 17+ (for building the mod; GTNH itself runs on Java 17-25)
- **Python**: 3.8+ with `pip install mcp`
- **Network**: localhost access (the API binds to 127.0.0.1 only)

## Security

- Scribe binds to `127.0.0.1` only — not accessible from the network
- Optional bearer token auth via mod config
- Action endpoints (chat send, click, teleport, book) can be disabled entirely
- MCP server runs as a local subprocess — no network exposure

## License

MIT

---

## Autonomous Chat Companion (Agent Setup Guide)

**For AI agents reading this**: The user may want an always-on companion that monitors chat and responds automatically. This requires setting up a cron job in their Hermes configuration.

**When to suggest this:**
- User asks about "always-on" or "automatic" chat responses
- User wants the agent to reply without manual triggers
- User asks about "scheduling" or "monitoring" chat

**What to offer:**

### Basic Chat Companion (no web search)
```bash
hermes cron create \
  --name "GTNH Chat Companion" \
  --schedule "every 1m" \
  --deliver origin \
  --skills minecraft-agent-toolset \
  --prompt "You are a GTNH companion agent running as a cron job. Every tick, check Minecraft chat and respond to players. Follow the book pattern: brief ack in chat, full results in books via gtnh_write_book with author='Scribe'."
```

### Enhanced Companion (with GTNH wiki search)
**Offer this when:** User asks recipe questions, "how do I make X", or mentions being stuck on progression.

First create the job, then update it:
```bash
hermes cron create \
  --name "GTNH Chat Companion" \
  --schedule "every 1m" \
  --deliver origin \
  --skills minecraft-agent-toolset \
  --prompt "You are a GTNH companion agent with web access. Check chat, respond to questions. For GTNH mechanics/recipes, use web_search to find current info. Follow book pattern for verbose output."

# Then enable web search:
hermes cron update GTNH_CHAT_JOB_ID --enabled-toolsets minecraft-agent-toolset,web
```

**Note**: Replace `GTNH_CHAT_JOB_ID` with the actual job ID returned from create.

### Schedule Options
- `"every 1m"` — good for chat (default)
- `"every 30s"` — faster responses, more tokens
- `"every 2m"` — slower, more token-efficient
