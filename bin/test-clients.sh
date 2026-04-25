#!/usr/bin/env bash
# E2E test: run all four examples (bash CLI, Python, Go, TS) against the live
# btone-mod-c bridge and assert each prints OK.
#
# Prereqs:
#   - MC client running with btone-mod-c loaded
#   - bin/btone-cli built (cd clients/go && nix develop ../.. --command go build -o ../../bin/btone-cli ./cmd/btone-cli)
#   - clients/typescript/node_modules installed (npm install in that dir)
#
# Run from repo root:
#   bin/test-clients.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CFG="$HOME/btone-mc-work/config/btone-bridge.json"
[[ -f "$CFG" ]] || { echo "FAIL: missing $CFG (is MC running?)"; exit 1; }

PORT=$(jq -r .port "$CFG")
TOKEN=$(jq -r .token "$CFG")
if ! curl -s --max-time 3 -X POST "http://127.0.0.1:$PORT/rpc" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"method":"player.state"}' | jq -e '.ok' >/dev/null; then
    echo "FAIL: bridge not responding on port $PORT"
    exit 1
fi

declare -i pass=0 fail=0
run() {
    local name="$1"; shift
    echo
    echo "================ $name ================"
    if "$@"; then
        echo "[$name] PASS"
        pass+=1
    else
        echo "[$name] FAIL (exit $?)"
        fail+=1
    fi
}

run bash       bash    examples/bash-cli.sh
run python     env PYTHONPATH=clients/python python3 examples/python-example.py
run go         bash -c "cd clients/go && nix develop $ROOT --command go run $ROOT/examples/go-example.go"
run typescript bash -c "cd clients/typescript && nix develop $ROOT --command ./node_modules/.bin/tsx $ROOT/examples/ts-example.ts"

echo
echo "==========================================="
echo "RESULTS: pass=$pass fail=$fail"
echo "==========================================="
exit $(( fail > 0 ? 1 : 0 ))
