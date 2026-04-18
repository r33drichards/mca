# btone

Two parallel experiments in driving a modded Minecraft 1.21.8 client (Fabric + Baritone, optional Meteor) from an MCP-aware agent. Built to compare two architectures.

## Option B — [`docs/plans/2026-04-18-option-b-eval-mod.md`](docs/plans/2026-04-18-option-b-eval-mod.md)

Fabric mod exposes a single MCP tool, `eval`. The agent writes Kotlin. The mod compiles and runs it inside the Minecraft JVM with implicit access to `mc`, `baritone`, `meteor`, `events`, `registerCleanup`. MCP server is embedded in the mod (Streamable HTTP on `127.0.0.1:25590`). Maximum power — the agent can hook events, register Meteor modules, reach into internals.

## Option C — [`docs/plans/2026-04-18-option-c-data-pipe-mod.md`](docs/plans/2026-04-18-option-c-data-pipe-mod.md)

Fabric mod exposes a fixed RPC surface (`POST /rpc`, `GET /events`) on `127.0.0.1:25591`. No scripting in the JVM. The agent drives the mod by writing JavaScript in **mcp-v8** (https://github.com/r33drichards/mcp-js), which `fetch()`es the mod. Tests the hypothesis that a rich-enough primitive set + a V8 runtime gets you everywhere JVM eval would.

## Layout

```
docs/plans/               implementation plans, one per option
mod-b/                    Option B Fabric mod (Kotlin)
mod-c/                    Option C Fabric mod (Java)
stdio-proxy/              Option B stdio→HTTP MCP proxy (Node)
btone-c-runner/           Option C mcp-v8 launcher + example scripts
```

Both options target Minecraft 1.21.8 / Fabric Loader 0.16+ / Java 21 / Baritone 1.15.0.
