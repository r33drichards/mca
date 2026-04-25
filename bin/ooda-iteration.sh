#!/usr/bin/env bash
# OODA loop iteration runner for stone mining
# This script bypasses the nix libstdbuf issue by using native macOS tools

set -euo pipefail

CFG="$HOME/btone-mc-work/config/btone-bridge.json"
SKILL_DIR="$HOME/mca/.claude/skills/btone-stone-mining"

# Read config using Python (available system-wide)
PORT=$(python3 -c "import json; print(json.load(open('$CFG'))['port'])")
TOKEN=$(python3 -c "import json; print(json.load(open('$CFG'))['token'])")
BASE="http://127.0.0.1:$PORT"

# RPC helper using system curl
rpc() {
  /usr/bin/curl -s -X POST "$BASE/rpc" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$1"
}

# OBSERVE
echo "=== OBSERVE ==="
STATE=$(rpc '{"method":"player.state"}')
INV=$(rpc '{"method":"player.inventory"}')

echo "STATE: $STATE" | head -c 500
echo ""
echo "INV: $INV" | head -c 500
echo ""

# Parse critical values with python
HP=$(echo "$STATE" | python3 -c "import sys, json; print(json.load(sys.stdin)['result']['health'])")
IN_WORLD=$(echo "$STATE" | python3 -c "import sys, json; print(json.load(sys.stdin)['result']['inWorld'])")
POS=$(echo "$STATE" | python3 -c "import sys, json; p=json.load(sys.stdin)['result']['blockPos']; print(f\"({p['x']}, {p['y']}, {p['z']})\")")

# Armor check
ARMOR_CHEST=$(echo "$STATE" | python3 -c "import sys, json; armor=[s for s in json.load(sys.stdin)['result'].get('armorSlots',[]) if s.get('slot')=='CHEST']; print(len(armor))")
ARMOR_LEGS=$(echo "$STATE" | python3 -c "import sys, json; armor=[s for s in json.load(sys.stdin)['result'].get('armorSlots',[]) if s.get('slot')=='LEGS']; print(len(armor))")
ARMOR_BOOTS=$(echo "$STATE" | python3 -c "import sys, json; armor=[s for s in json.load(sys.stdin)['result'].get('armorSlots',[]) if s.get('slot')=='FEET']; print(len(armor))")
ARMOR_OK=$([[ "$ARMOR_CHEST" -gt 0 && "$ARMOR_LEGS" -gt 0 && "$ARMOR_BOOTS" -gt 0 ]] && echo 1 || echo 0)

# Inventory counts
PICKS=$(echo "$INV" | python3 -c "import sys, json; inv=json.load(sys.stdin)['result']['main']; print(len([i for i in inv if 'pickaxe' in i.get('id','')]))")
FOOD=$(echo "$INV" | python3 -c "import sys, json; inv=json.load(sys.stdin)['result']['main']; print(len([i for i in inv if any(x in i.get('id','') for x in ['golden_carrot','cooked_'])]))")
USED=$(echo "$INV" | python3 -c "import sys, json; print(len(json.load(sys.stdin)['result']['main']))")
COBBLE=$(echo "$INV" | python3 -c "import sys, json; inv=json.load(sys.stdin)['result']['main']; print(sum(i.get('count',0) for i in inv if any(x in i.get('id','') for x in ['cobblestone','stone'])))")

echo "OODA OBSERVE: hp=$HP inWorld=$IN_WORLD pos=$POS armor=$ARMOR_OK picks=$PICKS food=$FOOD inv=$USED/36 cobble=$COBBLE"

# ORIENT (read scratch.yaml using Python since yq might not be available)
PHASE=$(python3 -c "import yaml; print(yaml.safe_load(open('$SKILL_DIR/scratch.yaml'))['phase'])" 2>/dev/null || echo "IDLE")
NEXT_ACTION=$(python3 -c "import yaml; print(yaml.safe_load(open('$SKILL_DIR/scratch.yaml'))['next_action'])" 2>/dev/null || echo "preflight")

echo "OODA ORIENT: phase=$PHASE next_action=$NEXT_ACTION"

# DECIDE
SAFETY=""
[[ "$HP" == "0.0" || "$IN_WORLD" == "false" ]] && SAFETY="DEAD"
[[ "$ARMOR_OK" == "0" ]] && SAFETY="NO_ARMOR"
[[ "$PICKS" -le 1 ]] && SAFETY="LOW_PICKS"
[[ "$FOOD" -eq 0 ]] && SAFETY="NO_FOOD"

if [[ -n "$SAFETY" ]]; then
  echo "OODA DECIDE: SAFETY → $SAFETY. Next action: RESUPPLY"

  # ACT: Handle safety issues
  case "$SAFETY" in
    DEAD)
      echo "Bot is dead, respawning..."
      rpc '{"method":"player.respawn"}'
      sleep 1
      echo "Phase → RESUPPLY"
      ;;
    NO_ARMOR|LOW_PICKS|NO_FOOD)
      echo "Missing critical resources, phase → RESUPPLY"
      ;;
  esac

  echo "Summary: Safety issue detected ($SAFETY), will resupply next iteration"

else
  echo "OODA DECIDE: Safety OK. Proceeding with phase=$PHASE work."

  # ACT: Execute phase-specific work
  case "$PHASE" in
    IDLE)
      echo "IDLE → Enabling Meteor modules and Baritone settings..."
      for m in auto-eat auto-armor auto-tool auto-replenish auto-respawn kill-aura anti-hunger; do
        rpc "{\"method\":\"meteor.module.enable\",\"params\":{\"name\":\"$m\"}}" >/dev/null
      done
      rpc '{"method":"baritone.command","params":{"text":"set allowBreak true"}}' >/dev/null
      rpc '{"method":"baritone.command","params":{"text":"set allowPlace true"}}' >/dev/null
      echo "Setup complete, next phase: WALK_OUT"
      ;;

    WALK_OUT)
      echo "WALK_OUT → Navigating to bridge east end..."
      # Three-hop: island anchor → bridge east → landmass
      rpc '{"method":"baritone.goto","params":{"x":588,"y":68,"z":829}}' >/dev/null
      sleep 1
      echo "Walking to bridge east end, waiting for next iteration to continue..."
      ;;

    MINE)
      echo "MINE → Starting mining routine..."
      # Check if we're at the right location first
      if [[ "$USED" -ge 34 ]]; then
        echo "Inventory full ($USED/36), transitioning to WALK_HOME"
      else
        echo "Starting mine command for stone/ore..."
        rpc '{"method":"baritone.command","params":{"text":"mine minecraft:stone minecraft:cobblestone minecraft:copper_ore minecraft:coal_ore minecraft:iron_ore"}}' >/dev/null
        echo "Mining started, will poll next iteration"
      fi
      ;;

    WALK_HOME)
      echo "WALK_HOME → Returning to W2..."
      rpc '{"method":"baritone.stop"}' >/dev/null
      sleep 0.5
      rpc '{"method":"baritone.goto","params":{"x":1007,"y":68,"z":829}}' >/dev/null
      echo "Walking home via bridge, waiting for next iteration..."
      ;;

    STORE)
      echo "STORE → Depositing items at W2..."
      echo "Will implement chest interaction in next iteration"
      ;;

    RESUPPLY)
      echo "RESUPPLY → Getting gear from loot wall..."
      rpc '{"method":"baritone.goto","params":{"x":567,"y":66,"z":910}}' >/dev/null
      echo "Walking to loot wall, will grab items next iteration"
      ;;

    *)
      echo "Unknown phase: $PHASE, resetting to IDLE"
      ;;
  esac
fi

echo ""
echo "=== Iteration complete ==="
