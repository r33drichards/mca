#!/bin/bash
# Unattended stone-mining loop. Pipes the btone-stone-mining SKILL.md
# into a fresh `claude` process on every iteration. Each invocation
# reads the skill, reads the Agent Scratch Pad at the bottom, does ONE
# phase-step, edits the scratch pad with new state, then exits. This
# script restarts it so the work continues across model-context limits.
#
# Usage:
#   bin/stone-mine-loop.sh            # run continuously
#   bin/stone-mine-loop.sh -n 20      # run at most 20 iterations
#
# Output: everything the inner `claude` prints goes straight to this
# script's stdout + is tee-d to /tmp/mine-loop.log.

set -u

SKILL="${SKILL:-$HOME/mca/.claude/skills/btone-stone-mining/SKILL.md}"
LOG="${LOG:-/tmp/mine-loop.log}"
SLEEP_BETWEEN="${SLEEP_BETWEEN:-5}"
MAX_ITER=""

while [ $# -gt 0 ]; do
  case "$1" in
    -n|--max) MAX_ITER="$2"; shift 2 ;;
    --skill) SKILL="$2"; shift 2 ;;
    --log) LOG="$2"; shift 2 ;;
    --sleep) SLEEP_BETWEEN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done

if [ ! -f "$SKILL" ]; then
  echo "SKILL file not found: $SKILL" >&2
  exit 1
fi
if ! command -v claude >/dev/null 2>&1; then
  echo "claude CLI not on PATH" >&2
  exit 1
fi

: > "$LOG"

echo "=========================================="
echo " btone stone-mine-loop starting"
echo " pid:        $$"
echo " skill:      $SKILL"
echo " log:        $LOG"
echo " max iter:   ${MAX_ITER:-unlimited}"
echo "=========================================="

ITER=0
while :; do
  ITER=$((ITER + 1))

  if [ -n "$MAX_ITER" ] && [ "$ITER" -gt "$MAX_ITER" ]; then
    echo ""
    echo "[loop] hit max iterations ($MAX_ITER) — exiting"
    exit 0
  fi

  START=$(date +%s)
  echo ""
  echo "=========================================="
  echo "[iter $ITER  $(date '+%F %T')]"
  echo "=========================================="

  # --print: single-response non-interactive mode, exits when done.
  # --dangerously-skip-permissions: no interactive tool-use prompts
  #   (required for an unattended loop).
  # stdbuf -oL: line-buffer claude's stdout so we see output as it arrives
  #   instead of at EOF.
  # tee: print to our terminal AND append to the persistent log.
  stdbuf -oL claude \
    --print \
    --dangerously-skip-permissions \
    < "$SKILL" 2>&1 \
  | tee -a "$LOG"
  EC=$?

  END=$(date +%s)
  echo ""
  echo "[iter $ITER done in $((END - START))s, exit=$EC]"

  if [ "$EC" -ne 0 ]; then
    echo "[loop] claude exited non-zero ($EC) — sleeping longer before retry"
    sleep 30
  fi

  sleep "$SLEEP_BETWEEN"
done
