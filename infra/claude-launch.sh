#!/usr/bin/env bash
# Launched inside tmux by claude-tmux.service. Drops claudeop into a
# srt-sandboxed Claude Code shell pointed at the codebase.
#
# This script runs INSIDE tmux but OUTSIDE the sandbox; everything it
# spawns (Claude + Claude's subprocesses) inherits srt's bubblewrap
# isolation.
set -e
cd /var/lib/btone/source
export PATH="/var/lib/btone/source/bin:$PATH"
exec srt --settings /etc/claude/srt-profile.json -- claude
