# Agent Setup Guide

How to connect an AI agent to Scribe — platform-agnostic, model-agnostic.

## Architecture

```
AI Agent (any platform: Claude, Codex, OpenClaw, etc.)
  └── MCP (stdio) → gtnh_chat_mcp.py
        └── HTTP (localhost:25566) → Scribe mod
              └── Minecraft + GTNH
```

The MCP server (`mcp-server/gtnh_chat_mcp.py`) bridges any MCP-compatible agent to the Scribe mod's HTTP API. No agent-specific code in the mod — it's just a Forge mod with a REST API.

## Prerequisites

1. **Scribe mod v1.1.0+** installed in GTNH `mods/` folder
2. **Minecraft running** with a world loaded (API on `localhost:25566`)
3. **Python 3.10+** with `pip install mcp`
4. **MCP-compatible agent platform** (Claude Desktop, Codex CLI, Hermes, OpenClaw, etc.)

## MCP Server Tools

| Tool | What it does |
|------|-------------|
| `gtnh_chat_read` | Read new chat messages since last poll |
| `gtnh_chat_send` | Send a message into Minecraft chat |
| `gtnh_write_book` | Create a written book (50 pages × 256 chars) |
| `gtnh_player_status` | Player position, health, food, XP |
| `gtnh_inventory` | Full inventory listing |
| `gtnh_scan_blocks` | Survey blocks around player (radius up to 32) |
| `gtnh_teleport` | Teleport to exact coordinates |
| `gtnh_click_block` | Right-click or left-click a block |
| `gtnh_ping` | Health check — is Minecraft reachable? |

## In-Game Commands

| Command | Effect |
|---------|--------|
| `/scribe` | Show available subcommands |
| `/scribe guide` | Get the instruction book |
| `/scribe talk on` | Listen to all public chat (default) |
| `/scribe talk off` | Only respond to `@scribe` messages |
| `/scribe <message>` | Send a direct message to the agent |

**`@scribe` prefix:** Type `@scribe scan for ores` in chat for private messages that other players don't see.

## The Book Pattern

Minecraft chat caps at ~100 characters. For verbose output, use books:

```
Chat: brief acks ("On it", "Report ready — check your inventory")
Book: full reports (50 pages × 256 chars = ~12,800 chars total)
```

Books appear in the player's inventory. Author should be `"Scribe"`.

## Autonomous Mode (Scheduled Polling)

For an always-on companion that monitors chat and responds automatically, set up a scheduled task that runs the agent every N seconds.

### Working Prompt Template

**Keep it short and imperative.** Long prompts cause the agent to describe actions instead of calling tools.

```
You are a GTNH companion. Every tick, check chat and respond.

TOOLS: gtnh_chat_read, gtnh_chat_send, gtnh_write_book (author="Scribe"),
gtnh_player_status, gtnh_inventory, gtnh_scan_blocks, gtnh_teleport,
gtnh_click_block, web_search

RULES:
1. Call gtnh_chat_read. If no messages, say [SILENT].
2. If there ARE messages, respond to the most recent one using gtnh_chat_send.
3. For GTNH questions, web_search first, then answer.
4. For complex answers, use gtnh_write_book then gtnh_chat_send "Check your inventory!"
5. NEVER use § codes. Plain text only.
```

### Schedule Tuning

| Interval | Use case |
|----------|----------|
| 30s | Fast responses, higher token cost |
| 1m | Good balance (recommended) |
| 2m | Token-efficient, slower responses |

### Platform-Specific Setup

<details>
<summary>Hermes</summary>

```bash
hermes cron create \
  --name "GTNH Chat Companion" \
  --schedule "every 1m" \
  --skills minecraft-agent-toolset \
  --prompt "<prompt from template above>"

# Add web search
hermes cron update <job-id> --enabled-toolsets minecraft-agent-toolset,web
```

MCP config in `config.yaml`:
```yaml
mcp_servers:
  gtnh:
    command: python
    args:
      - path/to/gtnh_chat_mcp.py
    env:
      GTNH_MCP_BASE_URL: http://127.0.0.1:25566
```
</details>

<details>
<summary>Claude Desktop</summary>

Add to `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "gtnh": {
      "command": "python",
      "args": ["path/to/gtnh_chat_mcp.py"],
      "env": {
        "GTNH_MCP_BASE_URL": "http://127.0.0.1:25566"
      }
    }
  }
}
```
</details>

<details>
<summary>Other platforms (Codex, OpenClaw, etc.)</summary>

Any MCP-compatible platform can use the server. Configure:
- **Command:** `python`
- **Args:** `["path/to/gtnh_chat_mcp.py"]`
- **Env:** `GTNH_MCP_BASE_URL=http://127.0.0.1:25566`

The MCP server advertises all tools via `tools/list`. No platform-specific code needed.
</details>

## Direct Python Client (no MCP)

```python
import sys
sys.path.insert(0, "path/to/mcp-server")
from gtnh_chat_mcp import HermesLink

link = HermesLink("http://127.0.0.1:25566")
link.ping()              # health check
link.player()            # all online players
link.inventory()         # full inventory
link.send_chat("hello")  # send chat
link.move(x, y, z)       # teleport
link.click(x, y, z, "right")  # interact with block
link.chat_poll(since=0, timeout=5000)  # long-poll new messages
```

## Agent Loop Pattern

```
1. gtnh_chat_read  → see what players are saying
2. If question: gtnh_chat_send to respond
3. If action: gtnh_player_status / gtnh_inventory / gtnh_scan_blocks
4. gtnh_teleport / gtnh_click_block to execute
5. Report back via gtnh_chat_send or gtnh_write_book
```

## Pitfalls

### Chat & Communication

- **Echo loop:** The cron agent responds to its own messages. Fix: prompt must instruct to only respond to the last 1-2 messages and ignore its own.
- **`§` codes render as glyphs:** Use plain text in chat. `EnumChatFormatting` works in the mod's command responses, but agent chat messages should be plain.
- **`/scribe` blocked in singleplayer:** Minecraft blocks all commands when "Allow Cheats: OFF". Fix: Esc → Open to LAN → Allow Cheats: ON.
- **Book pages overflow:** 256 chars of continuous text exceeds the book GUI height. The mod auto-wraps at ~19 chars/line, ~13 lines/page.

### Scheduling

- **Fresh sessions:** Each scheduled run is a fresh MCP server process. `_last_poll_ts` resets to 0. The server handles this by returning all messages on first poll — the prompt limits the agent to the last 1-2.
- **Poll window:** Don't use a narrow time window (e.g., "last 60s") — if chat is quiet, messages may be older than the window and get missed.

### Build & Install

- **JDK 17 required** for building (RetroFuturaGradle). JDK 21+ causes Gradle cache corruption.
- **Gradle 7.6+** required. The project uses RetroFuturaGradle, not vanilla ForgeGradle.
- **Remove old jars:** If `hermes-link-*.jar` and `scribe-*.jar` coexist, both mods load and conflict on port 25566.
- **Baritone doesn't exist for 1.7.10.** All movement is via teleport.

### Unicode & JSON

- Python's `json.dumps` adds a space after colons. The Java parser handles this.
- Unicode escapes (`\u2014`, `\u2022`, `\u201C`) in JSON are decoded by both BookHandler and ActionHandler.
