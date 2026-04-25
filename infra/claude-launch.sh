#!/usr/bin/env bash
# Launched inside tmux by claude-tmux.service. Drops claudeop into a
# srt-sandboxed Claude Code shell pointed at the codebase.
#
# Doesn't `exec` srt — wraps it in a relaunch loop that keeps the tmux
# pane alive and prints why srt/claude exited. Without this, a srt
# misconfig (bad profile, missing claude binary) kills the pane and
# `claude-peek` shows nothing useful.
set -u
cd /var/lib/btone/source
export PATH="/var/lib/btone/source/bin:/usr/local/bin:/usr/bin:/bin"

while true; do
  echo "[claude-launch] starting srt -- claude at $(date -u +%FT%TZ)"
  rc=0
  srt --settings /etc/claude/srt-profile.json -- claude || rc=$?
  echo "[claude-launch] srt -- claude exited rc=$rc at $(date -u +%FT%TZ)"
  echo "[claude-launch] sleeping 30s before relaunch (Ctrl-C to abort)"
  sleep 30
done
