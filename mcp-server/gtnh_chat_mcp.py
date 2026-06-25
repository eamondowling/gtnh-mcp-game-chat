#!/usr/bin/env python3
"""
GTNH Chat MCP Server — bridges an AI agent into Minecraft GTNH chat via the Scribe mod.

Architecture:
    AI Agent (Hermes, Claude, Codex, etc.)
      └── MCP (stdio transport)
            └── gtnh_chat_mcp.py  ← you are here
                  └── Scribe mod HTTP API (localhost:25566)
                        └── Minecraft + GTNH + Scribe mod

This MCP server exposes tools that let an agent:
- Read incoming chat messages (poll-based, non-blocking)
- Send chat messages as a player
- Read player state (position, health, inventory)
- Scan blocks around the player
- Teleport and click blocks

The agent appears as a player in Minecraft chat. Other players see its
messages and can talk to it. The agent reads the chat log and responds.

Cross-platform: the MCP server is pure Python (runs on Windows, macOS, Linux).
The Hermes Link mod is pure Java (runs anywhere Forge runs).

Requirements:
    pip install mcp
"""

import json
import time
import urllib.request
import urllib.error
import urllib.parse
import sys
import os
from typing import Optional, Any

# ═══════════════════════════════════════════════════════════════════════════════
# Hermes Link HTTP client (zero-dependency — stdlib only)
# ═══════════════════════════════════════════════════════════════════════════════

class HermesLink:
    """Thin HTTP client for the Hermes Link Forge mod API."""

    def __init__(self, base_url: str = "http://127.0.0.1:25566", token: str = ""):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def _get(self, path: str) -> dict:
        url = f"{self.base_url}{path}"
        req = urllib.request.Request(url)
        req.add_header("Accept", "application/json")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            body = e.read().decode() if e.fp else ""
            return {"error": f"HTTP {e.code}: {body}"}
        except urllib.error.URLError as e:
            return {"error": f"Connection failed: {e.reason}"}
        except Exception as e:
            return {"error": str(e)}

    def _post(self, path: str, data: dict) -> dict:
        url = f"{self.base_url}{path}"
        body = json.dumps(data).encode("utf-8")
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            body = e.read().decode() if e.fp else ""
            return {"error": f"HTTP {e.code}: {body}"}
        except urllib.error.URLError as e:
            return {"error": f"Connection failed: {e.reason}"}
        except Exception as e:
            return {"error": str(e)}

    def ping(self) -> dict:
        return self._get("/")

    def player(self) -> dict:
        return self._get("/player")

    def inventory(self, player: str = None) -> dict:
        path = "/player/inventory"
        if player:
            path += f"?player={urllib.parse.quote(player)}"
        return self._get(path)

    def nearby(self, radius: int = 10) -> dict:
        return self._get(f"/player/nearby?radius={radius}")

    def block_at(self, x: int, y: int, z: int) -> dict:
        return self._get(f"/world/block?x={x}&y={y}&z={z}")

    def blocks_around(self, x: int, y: int, z: int, radius: int = 5) -> dict:
        return self._get(f"/world/blocks?x={x}&y={y}&z={z}&radius={radius}")

    def chat_history(self) -> dict:
        return self._get("/chat/history")

    def chat_poll(self, since: int = 0, timeout: int = 30000) -> dict:
        return self._get(f"/chat/poll?since={since}&timeout={timeout}")

    def send_chat(self, message: str) -> dict:
        return self._post("/action/chat", {"message": message})

    def click(self, x: int, y: int, z: int, button: str = "right") -> dict:
        return self._post("/action/click", {"x": x, "y": y, "z": z, "button": button})

    def move(self, x: float, y: float, z: float) -> dict:
        return self._post("/action/move", {"x": x, "y": y, "z": z})

    def write_book(self, title: str, author: str, pages: list[str]) -> dict:
        """Create a written book and give it to the player. Max 50 pages, 256 chars each."""
        return self._post("/action/book", {"title": title, "author": author, "pages": pages})


# ═══════════════════════════════════════════════════════════════════════════════
# MCP Server
# ═══════════════════════════════════════════════════════════════════════════════

# Configuration from environment variables (set in Hermes config.yaml)
BASE_URL = os.environ.get("GTNH_MCP_BASE_URL", "http://127.0.0.1:25566")
AUTH_TOKEN = os.environ.get("GTNH_MCP_TOKEN", "")

link = HermesLink(BASE_URL, AUTH_TOKEN)

# Track the last poll timestamp so we only get new messages each call
_last_poll_ts: int = 0

# When _last_poll_ts is 0 (fresh session), use a recent window instead
# of fetching ALL messages. Each cron run is a fresh session.
import time as _time
def _get_since_ts() -> int:
    global _last_poll_ts
    if _last_poll_ts == 0:
        # First call in this session — get all messages.
        # The agent prompt instructs to only respond to the last 1-2.
        return 0
    return _last_poll_ts


def _check_connected() -> str | None:
    """Returns error string if not connected, None if OK."""
    ping = link.ping()
    if "error" in ping:
        return f"Not connected to Minecraft: {ping['error']}"
    return None


# ── Tool implementations ────────────────────────────────────────────────────

def tool_ping() -> str:
    """Check if the MCP server can reach Minecraft."""
    result = link.ping()
    if "error" in result:
        return f"MINECRAFT OFFLINE: {result['error']}"
    return f"Connected to {result.get('mod', 'Scribe')} {result.get('version', '?')} — {len(result.get('endpoints', []))} endpoints available"


def tool_chat_read() -> str:
    """Read new chat messages since the last poll. Returns formatted chat log."""
    global _last_poll_ts

    err = _check_connected()
    if err:
        return err

    # Use /chat/poll with a short timeout. Each cron run is a fresh session
    # so _last_poll_ts starts at 0. _get_since_ts() limits the first poll
    # to the last 60 seconds to avoid flooding the agent with old messages.
    result = link.chat_poll(since=_get_since_ts(), timeout=3000)

    if "error" in result:
        return f"Error reading chat: {result['error']}"

    messages = result.get("messages", [])
    timed_out = result.get("timed_out", False)

    if not messages:
        if timed_out:
            return "(no new messages)"
        return "(no new messages)"

    # Update poll timestamp to the latest message
    for msg in messages:
        ts = msg.get("timestamp", 0)
        if ts > _last_poll_ts:
            _last_poll_ts = ts

    # Format as readable chat log
    lines = []
    for msg in messages:
        username = msg.get("username", "?")
        text = msg.get("message", "")
        lines.append(f"<{username}> {text}")

    return "\n".join(lines)


def tool_chat_send(message: str) -> str:
    """Send a chat message into Minecraft. The agent speaks as the player."""
    err = _check_connected()
    if err:
        return err

    if not message or not message.strip():
        return "Error: message cannot be empty"

    result = link.send_chat(message)
    if "error" in result:
        return f"Error sending chat: {result['error']}"

    return f"Sent: {message}"


def tool_player_status() -> str:
    """Get the player's current status: position, health, food, XP, dimension."""
    err = _check_connected()
    if err:
        return err

    result = link.player()
    if "error" in result:
        return f"Error: {result['error']}"

    players = result.get("players", [])
    if not players:
        return "No players online"

    lines = []
    for p in players:
        lines.append(
            f"{p['name']}: pos=({p['x']:.1f}, {p['y']:.1f}, {p['z']:.1f}) "
            f"dim={p['dimension']} hp={p['health']:.0f}/{p['maxHealth']:.0f} "
            f"food={p['foodLevel']} xp={p['xpLevel']} gm={p['gamemode']}"
        )

    return "\n".join(lines)


def tool_inventory() -> str:
    """Get the player's inventory — item names, counts, and slot positions."""
    err = _check_connected()
    if err:
        return err

    result = link.inventory()
    if "error" in result:
        return f"Error: {result['error']}"

    items = result.get("items", [])
    if not items:
        return "Inventory empty"

    sections: dict[str, list[str]] = {}
    for item in items:
        slot = item.get("slot", -1)
        if 0 <= slot <= 8:
            section = "hotbar"
        elif 9 <= slot <= 35:
            section = "main"
        elif 100 <= slot <= 103:
            section = "armor"
        else:
            section = "other"

        name = item.get("displayName", item.get("id", "?"))
        count = item.get("count", 0)
        damage = item.get("damage", 0)
        entry = f"  slot {slot}: {count}x {name}"
        if damage:
            entry += f" (dmg:{damage})"
        sections.setdefault(section, []).append(entry)

    lines = [f"Player: {result.get('player', '?')}"]
    for section in ["hotbar", "main", "armor", "other"]:
        if section in sections:
            lines.append(f"[{section}]")
            lines.extend(sections[section])

    return "\n".join(lines)


def tool_scan_blocks(radius: int = 10) -> str:
    """Scan all non-air blocks around the player. Returns block type counts."""
    err = _check_connected()
    if err:
        return err

    player_result = link.player()
    if "error" in player_result:
        return f"Error getting player position: {player_result['error']}"

    players = player_result.get("players", [])
    if not players:
        return "No players online"

    p = players[0]
    x, y, z = int(p["x"]), int(p["y"]), int(p["z"])

    if radius > 32:
        radius = 32

    result = link.blocks_around(x, y, z, radius)
    if "error" in result:
        return f"Error scanning blocks: {result['error']}"

    blocks = result.get("blocks", [])
    if not blocks:
        return f"No non-air blocks within radius {radius}"

    counts: dict[str, int] = {}
    for b in blocks:
        name = b.get("block", "unknown")
        counts[name] = counts.get(name, 0) + 1

    sorted_counts = sorted(counts.items(), key=lambda kv: -kv[1])

    lines = [f"Scanned {len(blocks)} non-air blocks within radius {radius} of ({x},{y},{z}):"]
    for name, count in sorted_counts[:30]:
        lines.append(f"  {count:>4}x {name}")
    if len(sorted_counts) > 30:
        lines.append(f"  ... and {len(sorted_counts) - 30} more types")

    return "\n".join(lines)


def tool_teleport(x: float, y: float, z: float) -> str:
    """Teleport the player to exact coordinates."""
    err = _check_connected()
    if err:
        return err

    result = link.move(x, y, z)
    if "error" in result:
        return f"Error teleporting: {result['error']}"

    return f"Teleported to ({x:.1f}, {y:.1f}, {z:.1f})"


def tool_click_block(x: int, y: int, z: int, button: str = "right") -> str:
    """Right-click or left-click a block at the given coordinates."""
    err = _check_connected()
    if err:
        return err

    if button not in ("right", "left"):
        return "Error: button must be 'right' or 'left'"

    result = link.click(x, y, z, button)
    if "error" in result:
        return f"Error clicking: {result['error']}"

    return f"{button}-clicked block at ({x}, {y}, {z})"


def tool_write_book(title: str, author: str, pages_json: str) -> str:
    """Create a written book and give it to the player. Pages is a JSON array of strings."""
    err = _check_connected()
    if err:
        return err

    if not title or not title.strip():
        return "Error: title cannot be empty"

    # Parse pages from JSON string
    try:
        pages = json.loads(pages_json)
    except json.JSONDecodeError as e:
        return f"Error parsing pages JSON: {e}"

    if not isinstance(pages, list):
        return "Error: pages must be a JSON array of strings"

    if not pages:
        return "Error: pages array cannot be empty"

    if len(pages) > 50:
        return f"Error: max 50 pages, got {len(pages)}"

    result = link.write_book(title, author or "Hermes", pages)
    if "error" in result:
        return f"Error writing book: {result['error']}"

    return f"Written book '{title}' created with {result.get('pages', len(pages))} pages — check your inventory"


# ═══════════════════════════════════════════════════════════════════════════════
# MCP Server Setup (stdio transport)
# ═══════════════════════════════════════════════════════════════════════════════

def main():
    try:
        from mcp.server import Server
        from mcp.server.stdio import stdio_server
        from mcp.types import Tool, TextContent
    except ImportError:
        print("FATAL: mcp package not installed. Run: pip install mcp", file=sys.stderr)
        sys.exit(1)

    import asyncio

    server = Server("gtnh-chat")

    @server.list_tools()
    async def list_tools() -> list[Tool]:
        return [
            Tool(
                name="gtnh_ping",
                description="Check if the MCP server can reach Minecraft (GTNH). Returns connection status and API version.",
                inputSchema={"type": "object", "properties": {}, "required": []},
            ),
            Tool(
                name="gtnh_chat_read",
                description="Read new chat messages from Minecraft since the last poll. Returns formatted chat log with usernames and messages. Call this to see what players are saying.",
                inputSchema={"type": "object", "properties": {}, "required": []},
            ),
            Tool(
                name="gtnh_chat_send",
                description="Send a chat message into Minecraft. The agent speaks as the player. Other players will see this message in chat.",
                inputSchema={
                    "type": "object",
                    "properties": {
                        "message": {
                            "type": "string",
                            "description": "The message to send into Minecraft chat",
                        }
                    },
                    "required": ["message"],
                },
            ),
            Tool(
                name="gtnh_player_status",
                description="Get the player's current status: position (x,y,z), dimension, health, food level, XP, and gamemode.",
                inputSchema={"type": "object", "properties": {}, "required": []},
            ),
            Tool(
                name="gtnh_inventory",
                description="Get the player's full inventory — item names, counts, damage values, and slot positions. Grouped by hotbar/main/armor.",
                inputSchema={"type": "object", "properties": {}, "required": []},
            ),
            Tool(
                name="gtnh_scan_blocks",
                description="Scan all non-air blocks around the player's current position. Returns counts by block type. Useful for surveying ores, structures, or base layout.",
                inputSchema={
                    "type": "object",
                    "properties": {
                        "radius": {
                            "type": "integer",
                            "description": "Scan radius in blocks (default 10, max 32)",
                            "default": 10,
                        }
                    },
                    "required": [],
                },
            ),
            Tool(
                name="gtnh_teleport",
                description="Teleport the player to exact coordinates. Instant — no pathfinding.",
                inputSchema={
                    "type": "object",
                    "properties": {
                        "x": {"type": "number", "description": "X coordinate"},
                        "y": {"type": "number", "description": "Y coordinate"},
                        "z": {"type": "number", "description": "Z coordinate"},
                    },
                    "required": ["x", "y", "z"],
                },
            ),
            Tool(
                name="gtnh_click_block",
                description="Right-click or left-click a block at the given coordinates. Right-click interacts (open chest, activate machine). Left-click starts breaking.",
                inputSchema={
                    "type": "object",
                    "properties": {
                        "x": {"type": "integer", "description": "X coordinate of the block"},
                        "y": {"type": "integer", "description": "Y coordinate of the block"},
                        "z": {"type": "integer", "description": "Z coordinate of the block"},
                        "button": {
                            "type": "string",
                            "description": "'right' to interact, 'left' to break",
                            "enum": ["right", "left"],
                            "default": "right",
                        },
                    },
                    "required": ["x", "y", "z"],
                },
            ),
            Tool(
                name="gtnh_write_book",
                description="Create a written book and give it to the player. Use for verbose reports that don't fit in chat — ore surveys, quest summaries, base layouts. Max 50 pages, 256 chars each. Pages is a JSON array of strings.",
                inputSchema={
                    "type": "object",
                    "properties": {
                        "title": {"type": "string", "description": "Book title"},
                        "author": {"type": "string", "description": "Author name (default: Hermes)", "default": "Hermes"},
                        "pages": {"type": "string", "description": "JSON array of page strings, e.g. '[\"page1\", \"page2\"]'"},
                    },
                    "required": ["title", "pages"],
                },
            ),
        ]

    @server.call_tool()
    async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
        tool_map = {
            "gtnh_ping": lambda: tool_ping(),
            "gtnh_chat_read": lambda: tool_chat_read(),
            "gtnh_chat_send": lambda: tool_chat_send(arguments.get("message", "")),
            "gtnh_player_status": lambda: tool_player_status(),
            "gtnh_inventory": lambda: tool_inventory(),
            "gtnh_scan_blocks": lambda: tool_scan_blocks(arguments.get("radius", 10)),
            "gtnh_teleport": lambda: tool_teleport(
                float(arguments.get("x", 0)),
                float(arguments.get("y", 64)),
                float(arguments.get("z", 0)),
            ),
            "gtnh_click_block": lambda: tool_click_block(
                int(arguments.get("x", 0)),
                int(arguments.get("y", 64)),
                int(arguments.get("z", 0)),
                arguments.get("button", "right"),
            ),
            "gtnh_write_book": lambda: tool_write_book(
                arguments.get("title", "Agent Report"),
                arguments.get("author", "Hermes"),
                arguments.get("pages", "[]"),
            ),
        }

        fn = tool_map.get(name)
        if fn is None:
            return [TextContent(type="text", text=f"Unknown tool: {name}")]

        try:
            result = fn()
            return [TextContent(type="text", text=result)]
        except Exception as e:
            return [TextContent(type="text", text=f"Tool error: {e}")]

    async def run():
        async with stdio_server() as (read_stream, write_stream):
            await server.run(read_stream, write_stream, server.create_initialization_options())

    asyncio.run(run())


if __name__ == "__main__":
    main()
