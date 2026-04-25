#!/usr/bin/env bash
# Example: drive btone-mod-c with the btone-cli binary.
# Build with: cd clients/go && go build -o ../../bin/btone-cli ./cmd/btone-cli
set -euo pipefail

BTONE="${BTONE:-$(dirname "$0")/../bin/btone-cli}"
[[ -x "$BTONE" ]] || { echo "missing btone-cli at $BTONE; build it first" >&2; exit 1; }

echo "=== bash example ==="

# 1. Self-introspect
N=$("$BTONE" rpc.discover | jq '.methods | length')
echo "discovered $N methods"

# 2. Read player state
"$BTONE" player.state | jq -c '{inWorld, name, hp:.health, food, pos:.blockPos}'

# 3. List a couple of items in inventory
"$BTONE" player.inventory | jq -c '.main | map({slot,id,count}) | .[0:3]'

# 4. Take a screenshot — pipe straight to a file
TMP=$(mktemp /tmp/btone-bash-shot.XXXXXX.png)
"$BTONE" world.screenshot --params '{"width":480,"yaw":180,"pitch":-5}' \
  | jq -r '.image' | base64 --decode > "$TMP"
echo "wrote $(stat -f%z "$TMP" 2>/dev/null || stat -c%s "$TMP") bytes to $TMP"

# 5. Describe a method (uses rpc.discover under the hood)
"$BTONE" describe player.state | jq -c '{name, summary, result:.result.name}'

echo "OK"
