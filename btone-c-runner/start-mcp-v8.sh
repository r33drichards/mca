#!/usr/bin/env bash
# Start mcp-v8 for the btone-mod-c agent loop.
#
# Heap files (the persistent JS sessions mcp-v8 manages) live under
# $HEAP_DIR; defaults to ~/.btone/mcp-v8-heaps. mcp-v8 speaks stdio
# by default, which is what most Claude Desktop / MCP-host configurations
# expect. Set HTTP_PORT to switch to its HTTP transport.
#
# Usage:
#   btone-c-runner/start-mcp-v8.sh                    # stdio
#   HTTP_PORT=25700 btone-c-runner/start-mcp-v8.sh    # HTTP on :25700
set -euo pipefail

HEAP_DIR="${HEAP_DIR:-$HOME/.btone/mcp-v8-heaps}"
mkdir -p "$HEAP_DIR"

if [[ -n "${HTTP_PORT:-}" ]]; then
    exec mcp-v8 --directory-path "$HEAP_DIR" --http-port "$HTTP_PORT"
else
    exec mcp-v8 --directory-path "$HEAP_DIR"
fi
