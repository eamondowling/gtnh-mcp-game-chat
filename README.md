# GTNH MCP Game Chat

An MCP (Model Context Protocol) server that bridges AI agents into Minecraft GTNH chat. The agent appears as a player — it reads chat messages, responds, and can control the game through a local HTTP API.

**Cross-platform** — the MCP server is pure Python (Windows, macOS, Linux). The Hermes Link mod is pure Java (anywhere Forge runs). Works with any MCP-compatible agent: Hermes, Claude, Codex, OpenClaw, Pi-agent, etc.

## Architecture

```
┌──────────────────────┐     MCP (stdio)      ┌──────────────────────┐
│   AI Agent            │ ◄─────────────────► │  gtnh_chat_mcp.py    │
│   (Hermes, Claude,    │                      │  (Python MCP server) │
│    Codex, OpenClaw,   │                      └──────────┬───────────┘
│    Pi-agent, etc.)    │                                 │
└──────────────────────┘                          HTTP (localhost:25566)
                                                         │
                                              ┌──────────▼───────────┐
                                              │  Hermes Link mod     │
                                              │  (Forge 1.7.10)      │
                                              │                      │
                                              │  Chat capture        │
                                              │  Player state        │
                                              │  Block scanning      │
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

## Quick Start

### 1. Build and install the mod

```bash
cd hermes-link
./gradlew build
# Copy build/libs/hermes-link-1.1.0.jar into your GTNH instance's mods/ folder
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

Start GTNH through Prism Launcher. Load a world. The Hermes Link API starts on `localhost:25566`.

### 5. Restart your agent

The MCP server connects on startup. Tools appear with the `gtnh_` prefix.

## How the agent uses it

```
Agent loop:
  1. gtnh_chat_read    → see what players are saying
  2. gtnh_chat_send    → respond to questions
  3. gtnh_player_status → check position, health
  4. gtnh_inventory    → check what you're carrying
  5. gtnh_scan_blocks  → survey the area
  6. gtnh_teleport     → move to a location
  7. gtnh_click_block  → interact with a machine or chest
```

## Project Structure

```
gtnh-mcp-game-chat/
├── README.md
├── hermes-link/                    # Forge 1.7.10 mod
│   ├── build.gradle
│   ├── gradlew / gradlew.bat
│   └── src/main/java/com/hermes/hermeslink/
│       ├── HermesLink.java         # @Mod entry point + config
│       ├── ApiServer.java          # HTTP server + chat endpoints
│       ├── chat/
│       │   └── ChatListener.java   # Forge event bus chat capture
│       └── api/
│           ├── PlayerHandler.java  # /player, /inventory, /nearby
│           ├── WorldHandler.java   # /world/block, /world/blocks
│           └── ActionHandler.java  # /action/chat, /click, /move
└── mcp-server/
    └── gtnh_chat_mcp.py            # MCP stdio server
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `GTNH_MCP_BASE_URL` | `http://127.0.0.1:25566` | Hermes Link API URL |
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

- Hermes Link binds to `127.0.0.1` only — not accessible from the network
- Optional bearer token auth via mod config
- Action endpoints (chat send, click, teleport) can be disabled entirely
- MCP server runs as a local subprocess — no network exposure

## License

MIT
