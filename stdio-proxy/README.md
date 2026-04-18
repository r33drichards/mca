# @btone/mcp-stdio-proxy

Tiny stdio-to-HTTP MCP proxy for hosts (e.g. Claude Desktop) that only speak
stdio. Forwards line-delimited JSON-RPC from stdin to btone-mod-b's
`http://127.0.0.1:<port>/mcp` endpoint and writes each response to stdout.

## Install

Either link it globally:

```bash
cd stdio-proxy
npm link        # exposes `btone-mcp-stdio` on PATH
```

Or invoke directly:

```bash
node /absolute/path/to/btone/stdio-proxy/index.mjs
```

Requires Node >= 20 (uses the global `fetch`).

## Configuration

The proxy needs the bridge port and bearer token, which the mod writes to
`<minecraft>/config/btone-bridge.json` on client init. By default the proxy
auto-discovers the newest `btone-bridge.json` under known Prism instance
roots:

- macOS: `~/Library/Application Support/PrismLauncher/instances/*/.minecraft/config/`
- Linux flatpak: `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/*/minecraft/config/`
- Linux native: `~/.local/share/PrismLauncher/instances/*/minecraft/config/`
- Windows: `%APPDATA%/PrismLauncher/instances/*/minecraft/config/`

If your launcher lives elsewhere (or you run multiple instances and want to
pin one), set `BTONE_CONFIG`:

```bash
BTONE_CONFIG=/path/to/btone-bridge.json btone-mcp-stdio
```

## Claude Desktop wiring

Edit `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "btone-b": {
      "command": "node",
      "args": ["/absolute/path/to/btone/stdio-proxy/index.mjs"]
    }
  }
}
```

Restart Claude Desktop. The `btone-b` server should appear with tools `eval`
and `eval_status`.

To pin a specific instance:

```json
{
  "mcpServers": {
    "btone-b": {
      "command": "node",
      "args": ["/absolute/path/to/btone/stdio-proxy/index.mjs"],
      "env": { "BTONE_CONFIG": "/absolute/path/to/btone-bridge.json" }
    }
  }
}
```

## Known limitations

- Single-shot per line: every stdin line is one HTTP POST whose response body
  is written verbatim to stdout. Fine for `tools/call`, `tools/list`, and
  other request/response RPCs.
- No bidirectional streaming, no SSE upgrade. Notifications the server might
  push outside of a request/response are not delivered. Use the mod's
  `/events` SSE endpoint directly if you need server-pushed events.
- Errors from the HTTP layer (Minecraft not running, port closed, bad token)
  surface as a JSON-RPC error reply with code `-32603`; the proxy stays up.
