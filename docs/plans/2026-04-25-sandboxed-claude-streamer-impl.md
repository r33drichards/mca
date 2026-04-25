# Sandboxed Claude on EC2 with Twitch streaming — implementation plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a sandboxed Claude Code instance to the existing EC2 bot host that can drive `bin/btone-cli`, edit the codebase, and start a Twitch livestream — without ever seeing the Twitch stream key or AWS IAM credentials.

**Architecture:** Three Linux uids (`btone` for the existing bot, `claudeop` for the sandboxed Claude, `streamd` for the Twitch key holder). Claude runs as `claudeop` inside `srt` (Anthropic's sandbox-runtime) which restricts FS/net via bubblewrap+seccomp on Linux. A small Python daemon owned by `streamd` listens on a Unix socket, accepts `START`/`STOP`/`STATUS` commands, and forks `ffmpeg` with the key from its environment. tmux hosts the Claude session for ssh-attach.

**Tech Stack:** Ubuntu 24.04 (existing), bubblewrap (via `@anthropic-ai/sandbox-runtime`), Python 3.12 (stdlib only) for the daemon, ffmpeg with `h264_nvenc` (already in deps), tmux, systemd, socat. Anthropic's `@anthropic-ai/claude-code` CLI for the Claude side.

**Reference design:** `docs/plans/2026-04-25-sandboxed-claude-streamer-design.md`.

**Source of truth for changes:** `infra/setup-ubuntu.sh`. Every infra change goes there so a fresh `bin/btone-ec2.sh setup` deploy reproduces it. Live-system experiments are fine for verification, but they MUST be backported to the script before the task is considered complete.

**Verification model:** No unit tests — this is infra. Each task ends by re-running setup on the live EC2 instance and confirming a specific behavior (a `systemctl status`, an `ls`, a sandboxed-shell test). The final task is the end-to-end smoke test.

---

## Task 0: Branch hygiene and prereqs

**Goal:** Confirm the working tree is in a state that's safe to commit small infra changes against `master` without dragging in the long-tailed mod-c WIP.

**Step 1: Verify current branch and stash any in-flight test files.**

Run:
```bash
git status --short | grep -v -E '^(M |A |D )' | head -20
```

Many `??` entries are expected (mod-c WIP, generated clients, etc.). Don't stage them. Confirm `bin/btone-ec2.sh ssh` still resolves the existing instance:
```bash
AWS_REGION=us-west-2 STACK_NAME=btone-ec2 bin/btone-ec2.sh ip
```
Expected: an IP like `54.245.246.129` (or whatever's live). If "stack not found", you'll need to re-deploy first via `bin/btone-ec2.sh provision && bin/btone-ec2.sh setup`.

**Step 2: Confirm a clean attach to the bot.**

```bash
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 BRIDGE_PORT=25592 bin/btone-ec2.sh tunnel
bin/btone-cli player.state | jq '.inWorld'
```
Expected: `true`. If false, fix that before continuing — the design assumes a live bot to test against.

**Step 3: No commit yet.** This is just the launch check.

---

## Task 1: Add `streamd` user, `streamcontrol` group, key path

**Goal:** Refactor the existing root-owned streaming service into a uid-separated layout. After this task the *streaming* still works the same way (still requires manual `systemctl enable --now btone-stream` after putting a key in the env file), but it runs as `streamd` instead of `root`.

**Files:**
- Modify: `infra/setup-ubuntu.sh:233-270` (the existing 12.5 streaming infra block)

**Step 1: Replace lines 233–270 of `infra/setup-ubuntu.sh` with this block.**

```bash
# --- 12.5 streaming infra (Twitch via ffmpeg + h264_nvenc) -----------------
# Runs as user `streamd` (separate uid). Group `streamcontrol` holds the
# users allowed to ask for stream commands (claudeop is added in task 4).
# The stream key lives in /etc/btone-stream/env (mode 0600 streamd:streamd)
# — only `streamd` and root can read it. Resolution matches the Xorg
# ModeLine; bumping to 1080p means re-rendering MC at 1080p too.
if ! id streamd >/dev/null 2>&1; then
  useradd -r -s /usr/sbin/nologin -d /var/lib/twitch-streamd streamd
fi
if ! getent group streamcontrol >/dev/null 2>&1; then
  groupadd streamcontrol
fi
install -d -o streamd -g streamd -m 0750 /var/lib/twitch-streamd
install -d -o root -g streamd -m 0750 /etc/btone-stream
if [ ! -f /etc/btone-stream/env ]; then
  install -m 0600 -o streamd -g streamd /dev/null /etc/btone-stream/env
  echo 'STREAM_KEY=' >/etc/btone-stream/env
  chown streamd:streamd /etc/btone-stream/env
  chmod 0600 /etc/btone-stream/env
fi

cat >/etc/systemd/system/btone-stream.service <<'EOF'
[Unit]
Description=Twitch RTMP streamer (x11grab :99 + h264_nvenc), runs as streamd
After=xorg-headless.service network-online.target btone-bot.service
Wants=network-online.target

[Service]
Type=simple
User=streamd
Group=streamd
SupplementaryGroups=video
EnvironmentFile=/etc/btone-stream/env
Environment=DISPLAY=:99
Environment=XAUTHORITY=/var/lib/twitch-streamd/.Xauthority
ExecStartPre=/bin/sh -c 'test -n "$STREAM_KEY" || { echo "STREAM_KEY empty in /etc/btone-stream/env" >&2; exit 1; }'
ExecStart=/usr/bin/ffmpeg -nostdin -loglevel warning -hide_banner \
  -f x11grab -framerate 30 -video_size 1280x720 -i :99 \
  -f lavfi -i anullsrc=r=44100:cl=stereo \
  -c:v h264_nvenc -preset p4 -tune ll -b:v 3500k -maxrate 3500k -bufsize 7000k \
  -g 60 -keyint_min 60 \
  -c:a aac -b:a 160k -ar 44100 \
  -f flv rtmp://live.twitch.tv/app/${STREAM_KEY}
Restart=on-failure
RestartSec=10s
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# Allow `streamd` to read the Xorg display :99. Xorg by default is
# accessible to root only; we add a group-readable xauth cookie. The
# helper unit below stamps a fresh cookie every time xorg-headless restarts.
cat >/etc/systemd/system/xauth-share.service <<'EOF'
[Unit]
Description=Stamp a shared xauth cookie for streamd to read DISPLAY=:99
After=xorg-headless.service
PartOf=xorg-headless.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/bin/install -m 0640 -o root -g streamd /dev/null /var/lib/twitch-streamd/.Xauthority
ExecStart=/usr/bin/sh -c 'XAUTHORITY=/var/lib/twitch-streamd/.Xauthority /usr/bin/xauth add :99 . $(/usr/bin/xxd -l 16 -p /dev/urandom)'
ExecStart=/usr/bin/sh -c 'DISPLAY=:99 XAUTHORITY=/var/lib/twitch-streamd/.Xauthority /usr/bin/xhost +SI:localuser:streamd >/dev/null'

[Install]
WantedBy=xorg-headless.service
EOF
```

(The xauth-share.service is the awkward part — Xorg started by `root` only allows root to connect. To let `streamd` open `:99`, we either run Xorg with `-allowMouseOpenFail -ac` (insecure: any local user can connect) or distribute an xauth cookie. We do the cookie.)

**Step 2: Apply on the instance.**

```bash
git add infra/setup-ubuntu.sh
git commit -m "infra: streaming service runs as streamd, not root"
git push origin master
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh setup
```

The setup script reruns; `streamd` user gets created; the unit is rewritten.

**Step 3: Verify.**

```bash
bin/btone-ec2.sh ssh
sudo id streamd
# Expected: uid=NNN(streamd) gid=NNN(streamd) ...
sudo getent group streamcontrol
# Expected: streamcontrol:x:NNN:
sudo ls -la /etc/btone-stream/env
# Expected: -rw------- 1 streamd streamd ... env
sudo cat /etc/systemd/system/btone-stream.service | grep -E 'User|Group'
# Expected: User=streamd / Group=streamd / SupplementaryGroups=video
```

**Step 4: Commit if you made any in-flight tweaks.** The commit from Step 2 should already be the only one, but if you fixed anything, commit + push + re-run setup.

---

## Task 2: Write the `twitch-streamd` daemon

**Goal:** Replace the "operator manually starts btone-stream" pattern with a daemon that owns the lifecycle and exposes a Unix socket so `claudeop` can ask it to start/stop without seeing the key.

**Files:**
- Create: `infra/twitch-streamd.py` (committed to repo, copied to `/usr/local/bin/twitch-streamd` by the setup script)
- Modify: `infra/setup-ubuntu.sh` (add the install + new service unit)

**Step 1: Write `infra/twitch-streamd.py`.**

```python
#!/usr/bin/env python3
"""twitch-streamd — a tiny daemon that holds the Twitch stream key and
manages an ffmpeg subprocess for streaming the headless Xorg display.

Listens on /run/twitch-streamd.sock (mode 0660 streamd:streamcontrol).
Line-based protocol, one client at a time:

    START    -> "ok started" | "ok already-running"
    STOP     -> "ok stopped" | "ok already-stopped"
    STATUS   -> "running pid=<N> uptime=<seconds>" | "stopped"

The STREAM_KEY env var is the only thing that ever holds the key. The
daemon never echoes it back over the socket. ffmpeg inherits it via
the RTMP URL constructed in this process.
"""

import os
import shlex
import socket
import subprocess
import sys
import threading
import time

SOCKET_PATH = "/run/twitch-streamd.sock"
DISPLAY = os.environ.get("DISPLAY", ":99")
XAUTHORITY = os.environ.get("XAUTHORITY", "/var/lib/twitch-streamd/.Xauthority")

_lock = threading.Lock()
_proc: subprocess.Popen | None = None
_started_at: float = 0.0


def _ffmpeg_argv(stream_key: str) -> list[str]:
    return [
        "/usr/bin/ffmpeg",
        "-nostdin", "-loglevel", "warning", "-hide_banner",
        "-f", "x11grab", "-framerate", "30", "-video_size", "1280x720",
        "-i", DISPLAY,
        "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
        "-c:v", "h264_nvenc", "-preset", "p4", "-tune", "ll",
        "-b:v", "3500k", "-maxrate", "3500k", "-bufsize", "7000k",
        "-g", "60", "-keyint_min", "60",
        "-c:a", "aac", "-b:a", "160k", "-ar", "44100",
        "-f", "flv", f"rtmp://live.twitch.tv/app/{stream_key}",
    ]


def _spawn() -> str:
    global _proc, _started_at
    key = os.environ.get("STREAM_KEY", "").strip()
    if not key:
        return "err no-stream-key"
    _proc = subprocess.Popen(
        _ffmpeg_argv(key),
        env={
            "DISPLAY": DISPLAY,
            "XAUTHORITY": XAUTHORITY,
            "PATH": "/usr/bin:/bin",
        },
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    _started_at = time.time()
    return f"ok started pid={_proc.pid}"


def _alive() -> bool:
    return _proc is not None and _proc.poll() is None


def cmd_start() -> str:
    with _lock:
        if _alive():
            return f"ok already-running pid={_proc.pid}"
        return _spawn()


def cmd_stop() -> str:
    global _proc
    with _lock:
        if not _alive():
            return "ok already-stopped"
        _proc.terminate()
        try:
            _proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            _proc.kill()
            _proc.wait()
        _proc = None
        return "ok stopped"


def cmd_status() -> str:
    with _lock:
        if not _alive():
            return "stopped"
        uptime = int(time.time() - _started_at)
        return f"running pid={_proc.pid} uptime={uptime}s"


HANDLERS = {"START": cmd_start, "STOP": cmd_stop, "STATUS": cmd_status}


def serve() -> None:
    if os.path.exists(SOCKET_PATH):
        os.unlink(SOCKET_PATH)
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.bind(SOCKET_PATH)
    os.chmod(SOCKET_PATH, 0o660)
    # group ownership comes from the streamcontrol group via the systemd
    # SupplementaryGroups directive — the socket inherits the daemon's
    # primary group (streamd), then a one-time chgrp aligns it.
    try:
        import grp
        gid = grp.getgrnam("streamcontrol").gr_gid
        os.chown(SOCKET_PATH, os.getuid(), gid)
    except KeyError:
        pass
    sock.listen(4)
    print(f"twitch-streamd listening on {SOCKET_PATH}", file=sys.stderr, flush=True)
    while True:
        client, _ = sock.accept()
        try:
            with client.makefile("rwb", buffering=0) as f:
                line = f.readline().decode("ascii", errors="replace").strip().upper()
                handler = HANDLERS.get(line)
                reply = handler() if handler else f"err unknown-command:{line!r}"
                f.write((reply + "\n").encode("ascii"))
        except Exception as e:
            print(f"client error: {e}", file=sys.stderr, flush=True)
        finally:
            client.close()


if __name__ == "__main__":
    serve()
```

**Step 2: Update `infra/setup-ubuntu.sh` to install the daemon and register a new systemd unit.**

Insert this block right after the existing `btone-stream.service` definition (around line 270, before the `xauth-share.service` block from Task 1):

```bash
# Daemon that owns the ffmpeg lifecycle and exposes a control socket.
# Replaces "operator runs systemctl enable btone-stream" with
# "claudeop writes START to /run/twitch-streamd.sock".
install -m 0755 -o root -g root \
  "/var/lib/btone/source/infra/twitch-streamd.py" \
  /usr/local/bin/twitch-streamd

cat >/etc/systemd/system/twitch-streamd.service <<'EOF'
[Unit]
Description=Twitch streaming control daemon (key holder, socket interface)
After=xorg-headless.service xauth-share.service network-online.target btone-bot.service
Wants=network-online.target xauth-share.service

[Service]
Type=simple
User=streamd
Group=streamd
SupplementaryGroups=streamcontrol video
EnvironmentFile=/etc/btone-stream/env
Environment=DISPLAY=:99
Environment=XAUTHORITY=/var/lib/twitch-streamd/.Xauthority
ExecStart=/usr/local/bin/twitch-streamd
RuntimeDirectory=
# Lock down — the daemon doesn't need much.
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
NoNewPrivileges=true
RestrictAddressFamilies=AF_UNIX AF_INET
Restart=on-failure
RestartSec=5s
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# The btone-stream.service from Task 1 is now superseded by twitch-streamd —
# disable + remove it so there's a single owner of the ffmpeg lifecycle.
systemctl disable --now btone-stream.service 2>/dev/null || true
rm -f /etc/systemd/system/btone-stream.service

systemctl daemon-reload
systemctl enable twitch-streamd.service
systemctl enable xauth-share.service
```

**Step 3: Commit + push + redeploy.**

```bash
git add infra/twitch-streamd.py infra/setup-ubuntu.sh
git commit -m "infra: add twitch-streamd daemon (Unix socket, key-isolated)"
git push origin master
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh setup
```

**Step 4: Verify the daemon is up.**

```bash
bin/btone-ec2.sh ssh
sudo systemctl status twitch-streamd
# Expected: active (running)
sudo ls -la /run/twitch-streamd.sock
# Expected: srw-rw---- 1 streamd streamcontrol ... twitch-streamd.sock
sudo journalctl -u twitch-streamd -n 5 --no-pager
# Expected: "twitch-streamd listening on /run/twitch-streamd.sock"

# Talk to it as root (a member of every group); we'll add claudeop later.
echo STATUS | sudo socat - UNIX-CONNECT:/run/twitch-streamd.sock
# Expected: stopped
```

(Don't try `START` yet — STREAM_KEY is still empty.)

**Step 5: Commit any fixes.**

---

## Task 3: Add `bin/twitch-stream` to the repo

**Goal:** A tiny wrapper script Claude calls inside the sandbox. Everything Claude needs to know about the streaming subsystem fits in a one-line socat invocation.

**Files:**
- Create: `bin/twitch-stream`

**Step 1: Write `bin/twitch-stream`.**

```bash
#!/usr/bin/env bash
# Control the Twitch streamer via /run/twitch-streamd.sock.
# Usage:
#   bin/twitch-stream START
#   bin/twitch-stream STOP
#   bin/twitch-stream STATUS    (default if no arg)
#
# Caller must be in the `streamcontrol` group. The daemon never echoes
# the stream key; STATUS only reports running/stopped + pid + uptime.
set -euo pipefail
exec socat - UNIX-CONNECT:/run/twitch-streamd.sock <<<"${1:-STATUS}"
```

**Step 2: Make it executable + commit.**

```bash
chmod +x bin/twitch-stream
git add bin/twitch-stream
git commit -m "bin/twitch-stream: thin client for the streaming daemon"
git push origin master
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh setup
```

(setup re-clones the repo on the instance, so the new script appears at `/var/lib/btone/source/bin/twitch-stream`.)

**Step 3: Verify on the instance.**

```bash
bin/btone-ec2.sh ssh
ls -la /var/lib/btone/source/bin/twitch-stream
# Expected: -rwxr-xr-x ... twitch-stream

# Stream key is still empty so START would fail; just check STATUS.
sudo /var/lib/btone/source/bin/twitch-stream STATUS
# Expected: stopped
```

---

## Task 4: Add `claudeop` user and group memberships

**Goal:** Create the user that the sandboxed Claude runs as. Member of `streamcontrol` so it can talk to twitch-streamd; nothing else special.

**Files:**
- Modify: `infra/setup-ubuntu.sh` (after the streamd block)

**Step 1: Add a new section to `infra/setup-ubuntu.sh`.**

Insert before `# --- 13. signal done ---`:

```bash
# --- 12.6 claudeop user (sandboxed Claude Code runs as this) ---------------
if ! id claudeop >/dev/null 2>&1; then
  useradd -m -s /bin/bash -d /home/claudeop claudeop
fi
usermod -aG streamcontrol claudeop
install -d -o claudeop -g claudeop -m 0755 /home/claudeop
```

**Step 2: Commit + redeploy + verify.**

```bash
git add infra/setup-ubuntu.sh
git commit -m "infra: add claudeop user, member of streamcontrol"
git push origin master
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh setup
```

```bash
bin/btone-ec2.sh ssh
sudo id claudeop
# Expected: groups include streamcontrol

# Test claudeop can talk to the daemon
sudo -u claudeop /var/lib/btone/source/bin/twitch-stream STATUS
# Expected: stopped

# Test claudeop CANNOT read the key
sudo -u claudeop cat /etc/btone-stream/env 2>&1 | head -1
# Expected: "Permission denied"
```

If both checks pass, this layer of the security model is verified.

---

## Task 5: Install `@anthropic-ai/sandbox-runtime` and Claude Code CLI

**Goal:** Make `srt` and `claude` available globally on the instance.

**Files:**
- Modify: `infra/setup-ubuntu.sh`

**Step 1: Add npm + the two packages to the apt + npm install steps.**

In `infra/setup-ubuntu.sh`, line ~30-39 (the apt-get install block), add:

```bash
  bubblewrap socat \
  nodejs npm \
```

(npm pulls Node 18+ from Ubuntu noble. If you need a newer Node, use NodeSource — not required for v1.)

After the apt block, before the streaming infra, add:

```bash
# --- 11.5 sandbox-runtime + claude code CLI --------------------------------
# Both are npm globals. Pin minor versions so reruns of setup don't
# silently upgrade the toolchain mid-session.
if ! command -v srt >/dev/null 2>&1; then
  npm install -g @anthropic-ai/sandbox-runtime
fi
if ! command -v claude >/dev/null 2>&1; then
  npm install -g @anthropic-ai/claude-code
fi
```

**Step 2: Commit + redeploy + verify.**

```bash
git add infra/setup-ubuntu.sh
git commit -m "infra: install srt + claude code CLI"
git push origin master
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh setup
```

```bash
bin/btone-ec2.sh ssh
which srt claude
# Expected: /usr/local/bin/srt and /usr/local/bin/claude (or under /usr/lib/node_modules)
srt --help | head -5
claude --version
```

---

## Task 6: Write the `srt` profile

**Goal:** The sandbox JSON. Allow Claude to do what it needs (codebase + bridge + stream control + Anthropic API), deny everything else (key, IMDS, other home dirs, /proc).

**Files:**
- Create: `infra/srt-profile.json` (committed to repo, copied to `/etc/claude/srt-profile.json` by setup)

**Step 1: Write `infra/srt-profile.json`.**

```json
{
  "filesystem": {
    "reads": {
      "allow": [
        "/var/lib/btone/source/**",
        "/home/claudeop/**",
        "/usr/bin/**",
        "/usr/lib/**",
        "/usr/local/bin/**",
        "/usr/share/**",
        "/etc/ssl/**",
        "/etc/resolv.conf",
        "/etc/hosts",
        "/etc/nsswitch.conf",
        "/etc/passwd",
        "/etc/group",
        "/lib/**",
        "/lib64/**",
        "/run/twitch-streamd.sock",
        "/proc/self/**",
        "/proc/cpuinfo",
        "/proc/meminfo",
        "/sys/devices/system/cpu/**"
      ],
      "deny": [
        "/etc/btone-stream/env",
        "/etc/btone-bot.env",
        "/etc/shadow",
        "/etc/sudoers",
        "/etc/sudoers.d/**",
        "/proc/[0-9]*/environ",
        "/proc/[0-9]*/maps",
        "/proc/[0-9]*/mem",
        "/proc/[0-9]*/cmdline",
        "/home/ubuntu/**",
        "/home/btone/**",
        "/home/streamd/**",
        "/var/lib/twitch-streamd/**",
        "/root/**"
      ]
    },
    "writes": {
      "allow": [
        "/var/lib/btone/source/**",
        "/home/claudeop/**",
        "/tmp/**",
        "/run/twitch-streamd.sock"
      ]
    }
  },
  "network": {
    "allow": [
      "api.anthropic.com",
      "claude.ai",
      "auth.anthropic.com",
      "127.0.0.1:25591",
      "github.com",
      "api.github.com",
      "objects.githubusercontent.com",
      "raw.githubusercontent.com",
      "codeload.github.com",
      "cdn.modrinth.com",
      "api.modrinth.com",
      "meteorclient.com"
    ],
    "deny": [
      "169.254.169.254",
      "169.254.170.2"
    ]
  },
  "unixSockets": {
    "connect": [
      "/run/twitch-streamd.sock"
    ]
  }
}
```

(Exact field names depend on srt's schema — verify by running `srt --print-default-settings` on the instance and matching the structure. If srt uses different keys like `fsReadAllowlist` or similar, adjust accordingly. This is the most likely place the plan will need a fix on first run.)

**Step 2: Update `infra/setup-ubuntu.sh` to install the profile.**

Add after the npm install block:

```bash
install -d -o root -g root -m 0755 /etc/claude
install -m 0644 -o root -g root \
  "/var/lib/btone/source/infra/srt-profile.json" \
  /etc/claude/srt-profile.json
```

**Step 3: Commit + redeploy.**

```bash
git add infra/srt-profile.json infra/setup-ubuntu.sh
git commit -m "infra: add srt sandbox profile for claudeop"
git push origin master
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh setup
```

**Step 4: Verify by running a sandboxed bash and probing what's reachable.**

```bash
bin/btone-ec2.sh ssh
sudo -u claudeop -H bash -c 'srt --settings /etc/claude/srt-profile.json -- bash -c "
  echo === codebase reachable ===
  ls /var/lib/btone/source/bin/btone-cli && echo OK
  echo === stream key denied ===
  cat /etc/btone-stream/env 2>&1 | head -1
  echo === IMDS denied ===
  curl -s --max-time 3 http://169.254.169.254/latest/meta-data/ 2>&1 | head -1
  echo === Anthropic API allowed ===
  curl -s -o /dev/null -w \"http %{http_code}\n\" --max-time 5 https://api.anthropic.com/
  echo === bridge allowed ===
  curl -s -o /dev/null -w \"http %{http_code}\n\" --max-time 5 http://127.0.0.1:25591/health
  echo === stream socket allowed ===
  echo STATUS | socat - UNIX-CONNECT:/run/twitch-streamd.sock
"'
```

Expected:
- `OK` for codebase
- `Permission denied` for the key
- timeout/blocked for IMDS
- `http 401` or similar for Anthropic (without auth)
- `http 401` or similar for the bridge (no token)
- `stopped` from the stream socket

If any line fails the wrong way, adjust the profile and re-run setup.

---

## Task 7: Write `claude-launch.sh` and `claude-tmux.service`

**Goal:** Boot a tmux session at machine start that runs the sandboxed Claude.

**Files:**
- Create: `infra/claude-launch.sh` (committed to repo, copied to `/usr/local/bin/`)
- Modify: `infra/setup-ubuntu.sh` (install the script + the service unit)

**Step 1: Write `infra/claude-launch.sh`.**

```bash
#!/usr/bin/env bash
# Launched inside tmux by claude-tmux.service. Drops claudeop into a
# srt-sandboxed Claude Code shell.
set -e
cd /var/lib/btone/source
exec srt --settings /etc/claude/srt-profile.json -- claude
```

**Step 2: Add to `infra/setup-ubuntu.sh`.**

```bash
# --- 12.7 claude-tmux service ----------------------------------------------
install -m 0755 -o root -g root \
  "/var/lib/btone/source/infra/claude-launch.sh" \
  /usr/local/bin/claude-launch.sh

cat >/etc/systemd/system/claude-tmux.service <<'EOF'
[Unit]
Description=Sandboxed Claude Code, tmux-hosted
After=network-online.target xorg-headless.service btone-bot.service twitch-streamd.service
Wants=network-online.target

[Service]
Type=forking
User=claudeop
Group=claudeop
Environment=HOME=/home/claudeop
ExecStart=/usr/bin/tmux -L claude new-session -d -s claude /usr/local/bin/claude-launch.sh
ExecStartPost=/usr/bin/tmux -L claude pipe-pane -t claude:0 -O 'cat >> /var/log/claude-session.log'
ExecStop=/usr/bin/tmux -L claude kill-server
RemainAfterExit=yes
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF

# Logfile is owned by claudeop so the pipe-pane can write to it.
install -m 0644 -o claudeop -g claudeop /dev/null /var/log/claude-session.log

systemctl daemon-reload
systemctl enable claude-tmux.service
```

**Step 3: Commit + redeploy.**

```bash
git add infra/claude-launch.sh infra/setup-ubuntu.sh
git commit -m "infra: claude-tmux.service hosts the sandboxed Claude Code"
git push origin master
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh setup
```

**Step 4: Start the service manually (setup enabled but didn't start it).**

```bash
bin/btone-ec2.sh ssh
sudo systemctl start claude-tmux
sudo systemctl status claude-tmux
# Expected: active (running)
sudo -u claudeop tmux -L claude list-sessions
# Expected: claude: 1 windows ...
```

**Step 5: Peek at what Claude is showing right now.**

```bash
sudo -u claudeop tmux -L claude capture-pane -t claude:0 -p | tail -20
```

You should see Claude Code's first-launch screen — likely "log in to claude.ai" with an OAuth code. Don't act on it yet; Task 8 wires up the attach flow.

---

## Task 8: Wire up `claude-attach`

**Goal:** A one-command attach for the operator from inside ssh.

**Files:**
- Modify: `infra/setup-ubuntu.sh`

**Step 1: Add to `infra/setup-ubuntu.sh`.**

```bash
# --- 12.8 operator attach helper -------------------------------------------
cat >/usr/local/bin/claude-attach <<'EOF'
#!/usr/bin/env bash
exec sudo -u claudeop tmux -L claude attach -t claude
EOF
chmod 0755 /usr/local/bin/claude-attach

cat >/etc/sudoers.d/claudeop <<'EOF'
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude attach -t claude
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude send-keys *
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude capture-pane -t claude:0 -p
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude list-sessions
EOF
chmod 0440 /etc/sudoers.d/claudeop
visudo -c -f /etc/sudoers.d/claudeop
```

**Step 2: Commit + redeploy + complete OAuth.**

```bash
git add infra/setup-ubuntu.sh
git commit -m "infra: claude-attach wrapper + sudoers"
git push origin master
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh setup
bin/btone-ec2.sh ssh
claude-attach
```

You're now in tmux, looking at Claude Code's first-run screen. Follow the OAuth device flow — open the URL it prints on your laptop, enter the code. Claude Code stores the token in `/home/claudeop/.claude/`. Detach with `Ctrl-b d`.

**Step 3: Verify the token persisted.**

```bash
sudo ls -la /home/claudeop/.claude/
# Expected: a credentials.json or session file
sudo systemctl restart claude-tmux
sleep 5
sudo -u claudeop tmux -L claude capture-pane -t claude:0 -p | tail -20
# Expected: a Claude Code prompt, not an OAuth screen
```

---

## Task 9: Add `bin/btone-ec2.sh claude` and `bin/btone-ec2.sh claude-send`

**Goal:** Local wrappers so the operator's laptop can attach without remembering the ssh + claude-attach dance, and so they can `tmux send-keys` from anywhere.

**Files:**
- Modify: `bin/btone-ec2.sh`

**Step 1: Add two new subcommands.**

Add inside the `case "$cmd" in` block (after `restart`):

```bash
  claude)     cmd_claude "$@" ;;
  claude-send) cmd_claude_send "$@" ;;
```

Add the two new functions (above `cmd_destroy`):

```bash
cmd_claude() {
  local ip
  ip=$(resolve_ip)
  exec ssh "${ssh_args[@]}" -t "$SSH_USER@$ip" claude-attach
}

cmd_claude_send() {
  local ip msg
  ip=$(resolve_ip)
  msg="$*"
  [[ -n "$msg" ]] || die "usage: $0 claude-send <text>"
  ssh "${ssh_args[@]}" "$SSH_USER@$ip" \
    "sudo -u claudeop tmux -L claude send-keys -t claude:0 -- $(printf '%q' "$msg") Enter"
}
```

Update the help section at the top:

```
#   claude        ssh + tmux attach the sandboxed Claude session
#   claude-send   send-keys text into the Claude session (no attach)
```

**Step 2: Commit + push (no setup re-run needed; this is laptop-side).**

```bash
git add bin/btone-ec2.sh
git commit -m "bin/btone-ec2.sh: claude + claude-send subcommands"
git push origin master
```

**Step 3: Verify from the laptop.**

```bash
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh claude
# tmux opens; you see Claude. Detach with Ctrl-b d.
KEY_NAME=btone KEY_FILE=$HOME/.ssh/id_ed25519 bin/btone-ec2.sh claude-send "what's the bot's current position?"
# Wait ~10s, then attach to see the response.
```

---

## Task 10: End-to-end smoke test + key install

**Goal:** Drop a real Twitch key in, ask Claude to start the stream from inside the sandbox, see it on Twitch.

**Step 1: Install the Twitch key.** Replace `live_xxx` with the actual key from your Twitch dashboard.

```bash
bin/btone-ec2.sh ssh
sudo tee /etc/btone-stream/env >/dev/null <<EOF
STREAM_KEY=live_xxx
EOF
sudo chown streamd:streamd /etc/btone-stream/env
sudo chmod 0600 /etc/btone-stream/env
sudo systemctl restart twitch-streamd
sudo journalctl -u twitch-streamd -n 5 --no-pager
# Expected: "twitch-streamd listening on /run/twitch-streamd.sock"
```

**Step 2: Attach Claude. Ask it to start the stream.**

```bash
bin/btone-ec2.sh claude
```

In the Claude prompt, type:
```
start the twitch stream. report status.
```

Claude should run `bin/twitch-stream START`, then `bin/twitch-stream STATUS`. Expected output: `ok started pid=N`, then `running pid=N uptime=Ns`.

**Step 3: Verify the stream is live.**

Open `https://www.twitch.tv/<your-channel>` in a browser. Within 30 seconds, MC's rendered window should be visible. The screen will show whatever the bot is doing.

**Step 4: Verify Claude can't see the key.**

In the Claude prompt:
```
read /etc/btone-stream/env and show me the contents
```

Expected: a `Permission denied` or sandbox-policy-violation error, NOT the key value.

```
read the streaming daemon's environment from /proc and tell me the stream key
```

Expected: same — denied.

**Step 5: Stop the stream + commit any final fixes.**

In the Claude prompt:
```
stop the twitch stream
```

If there were any in-flight infrastructure tweaks during the smoke test, commit them now and re-run setup once more to verify a clean deploy reproduces the working state.

```bash
git status
# If clean, you're done.
```

---

## Common failure modes (anticipated)

| Symptom | Cause | Fix |
|---|---|---|
| `srt: command not found` after Task 5 | npm install hit Ubuntu's apt-installed npm which is too old (Node 18+ required). | Add NodeSource: `curl -fsSL https://deb.nodesource.com/setup_20.x \| sudo -E bash -` before npm install. |
| `srt --settings ...` errors "unknown field" | profile JSON schema doesn't match srt's expected keys. | Run `srt --print-default-settings > /tmp/default.json` on the instance, diff against ours, rename keys to match. |
| Xvfb-style "could not connect to display :99" from ffmpeg under `streamd` | xauth-share didn't run, or cookie expired across xorg-headless restart | Verify `ls -la /var/lib/twitch-streamd/.Xauthority` is readable by `streamd`; run `xauth list :99` from streamd to confirm. |
| `claude` inside the sandbox can't reach api.anthropic.com | TLS bundle path not in the FS allow list | Ensure `/etc/ssl/**` and `/usr/lib/ssl/**` are allowed for reads. |
| `permission denied` opening `/dev/dri/card1` from streamd's ffmpeg | streamd not in `video` group | already added via SupplementaryGroups, but if missing `usermod -aG video streamd` then restart twitch-streamd. |
| `sudo: a password is required` when running claude-attach | sudoers fragment didn't validate; visudo -c failed silently. | Re-run setup; check `cat /etc/sudoers.d/claudeop` matches what's in the script. |
| Claude OAuth flow can't open URL on laptop | the URL prints fine; this is just an operator step. | Copy the URL from the tmux pane, paste into your laptop's browser. |
