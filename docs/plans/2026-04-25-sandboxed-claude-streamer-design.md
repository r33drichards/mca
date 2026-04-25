# Sandboxed Claude Code on EC2 with Twitch streaming

A second Claude Code instance runs on the EC2 bot host, sandboxed via
`@anthropic-ai/sandbox-runtime` (`srt`). It can drive the bot through
`bin/btone-cli`, edit the codebase, and start a Twitch livestream — but
cannot read the Twitch stream key, the EC2 IAM role credentials, or
files outside its allowlist.

The operator drives it by `ssh`-ing to the EC2 box and `tmux attach`-ing
to a long-running session.

## Goals and non-goals

**Goals:**
- Keep a Claude Code agent alive on EC2, ready to drive the bot
  whenever the operator (or another agent) wants to.
- Restrict that agent to: `bin/btone-cli`, the codebase, and its own
  scratchpad. Allow it to write new tools into `bin/`.
- Allow the agent to start/stop a Twitch livestream of the MC client
  without exposing the stream key.
- Keep the existing `btone-bot.service` + `xorg-headless.service`
  unchanged.

**Non-goals:**
- A web UI or HTTP API on top of Claude. SSH + tmux is the only
  remote-control surface.
- Network-isolating the bot itself (it already runs as `btone`, which
  is enough for this iteration).
- Running multiple sandboxed Claudes in parallel.

## Architecture

Five units on the EC2 host across three Linux uids:

| Unit | uid | Role |
|---|---|---|
| `xorg-headless.service` (existing) | `root` | headless Xorg on `:99`, Nvidia GPU |
| `btone-bot.service` (existing) | `btone` | MC client + RPC bridge on `127.0.0.1:25591` |
| `claude-tmux.service` (new) | `claudeop` | tmux server hosting the Claude session |
| `srt -- claude` inside tmux | `claudeop` | sandboxed Claude Code CLI |
| `twitch-streamd.service` (new) | `streamd` | guards Twitch key; ffmpeg → Twitch RTMP |

**uid separation** is the linchpin. Three users:

- `btone` — owns MC + bridge; nothing else changes.
- `claudeop` — runs Claude. Member of group `streamcontrol`. **Not** in
  `streamd`.
- `streamd` — owns `/etc/twitch.env` and the `ffmpeg` subprocess.
  No login shell.

## Components

### `claude-tmux.service`

Forks a detached tmux session as `claudeop`, runs
`srt --settings /etc/claude/srt-profile.json -- claude` inside.

```ini
[Unit]
Description=Sandboxed Claude Code, tmux-hosted
After=network-online.target xorg-headless.service btone-bot.service
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
```

`/usr/local/bin/claude-launch.sh`:
```bash
#!/usr/bin/env bash
set -e
exec srt --settings /etc/claude/srt-profile.json -- claude
```

### `srt` profile (`/etc/claude/srt-profile.json`)

**Filesystem reads — allow:**
- `/var/lib/btone/source/**` (codebase; read+write)
- `/home/claudeop/**` (scratchpad + `~/.claude/` for OAuth token; read+write)
- `/usr/bin/**`, `/usr/lib/**`, `/etc/ssl/**`, `/etc/resolv.conf`,
  `/usr/lib/jvm/java-21-*/**` (system libs)
- `/run/twitch-streamd.sock` (read+write)

**Filesystem reads — deny (override allow):**
- `/etc/twitch.env`
- `/etc/btone-bot.env`
- `/proc/[0-9]*` except own (block ptrace surface)
- `/home/ubuntu/**`, `/home/btone/**`, `/home/streamd/**`, `/root/**`
- `/etc/shadow`, `/etc/sudoers*`

**Filesystem writes — allow:**
- `/var/lib/btone/source/**` (so Claude can write new tools to `bin/`)
- `/home/claudeop/**`

**Filesystem writes — deny everywhere else.**

**Network — allow (egress):**
- `api.anthropic.com`, `claude.ai`, `auth.anthropic.com` (Claude API + OAuth)
- `127.0.0.1:25591` (MC bridge)
- `github.com`, `api.github.com`, `*.githubusercontent.com` (`git pull`/`push`)
- `cdn.modrinth.com`, `meteorclient.com`,
  `objects.githubusercontent.com` (mod downloads, only if Claude
  re-runs the deploy script)

**Network — deny:**
- `169.254.169.254` (EC2 IMDS — non-negotiable)
- everything else (default deny)

**Unix sockets:** allow connect to `/run/twitch-streamd.sock`. Disallow
socket *create* outside `/home/claudeop/`.

### `twitch-streamd.service` and the streaming daemon

`streamd` user (no login shell):
```bash
useradd -r -s /usr/sbin/nologin -d /var/lib/twitch-streamd streamd
groupadd streamcontrol
usermod -aG streamcontrol claudeop
```

`/etc/twitch.env` — `STREAM_KEY=live_xxxxx`, mode `0600 streamd:streamd`.

Daemon (`/usr/local/bin/twitch-streamd`, ~80 lines of Python):
- Listens on `/run/twitch-streamd.sock` (mode `0660 streamd:streamcontrol`).
- One client at a time, line-based protocol:
  - `START` → spawn ffmpeg subprocess if not running, return `ok started`
    (or `ok already-running`).
  - `STOP` → `SIGTERM` ffmpeg, wait, return `ok stopped`.
  - `STATUS` → `running pid=N uptime=Ns` or `stopped`.
- ffmpeg invocation:
  ```
  ffmpeg -loglevel warning \
    -f x11grab -framerate 30 -video_size 1280x720 -i :99.0 \
    -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=48000 \
    -c:v h264_nvenc -preset p3 -tune ll \
    -b:v 4500k -maxrate 4500k -bufsize 9000k -g 60 -keyint_min 60 \
    -c:a aac -b:a 64k -ar 48000 \
    -f flv rtmp://live.twitch.tv/app/$STREAM_KEY
  ```
  - `h264_nvenc` uses the T4's NVENC silicon — encoder doesn't fight MC
    for shader compute.
  - `lavfi anullsrc` is a silent audio source (OpenAL is muted on this
    box; using `snd-dummy` capture is fragile).

systemd unit:
```ini
[Unit]
Description=Twitch streaming daemon (holds key)
After=xorg-headless.service

[Service]
Type=simple
User=streamd
Group=streamd
SupplementaryGroups=streamcontrol
EnvironmentFile=/etc/twitch.env
ExecStart=/usr/local/bin/twitch-streamd
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
```

### `bin/twitch-stream` (in the codebase, accessible inside the sandbox)

```bash
#!/usr/bin/env bash
exec socat - UNIX-CONNECT:/run/twitch-streamd.sock <<<"${1:-STATUS}"
```

Claude inside the sandbox runs `bin/twitch-stream START`, sees `ok started`.
Cannot see the key — `/etc/twitch.env` denied; `/proc/<streamd-pid>`
denied; the socket protocol never echoes the key.

## Operator flow

```bash
# attach (after first OAuth setup, this is the daily flow)
bin/btone-ec2.sh ssh
claude-attach              # wraps `sudo -u claudeop tmux -L claude attach -t claude`
# Ctrl-b d to detach. Session keeps running.

# poke Claude programmatically (no attach)
ssh ubuntu@<ip> "sudo -u claudeop tmux -L claude send-keys -t claude:0 \
    'check bot health' Enter"
```

`/usr/local/bin/claude-attach`:
```bash
#!/usr/bin/env bash
exec sudo -u claudeop tmux -L claude attach -t claude
```

`/etc/sudoers.d/claudeop`:
```
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude attach -t claude
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude send-keys *
```

### First-run OAuth

On first start of `claude-tmux.service`, Claude prints a `claude.ai`
device-flow URL + 8-digit code to the tmux pty. The operator attaches,
opens the URL on their laptop, enters the code. The token is written
to `~claudeop/.claude/` and persists across service restarts.

## Security argument: why Claude can't see the Twitch key

Layered defense:

1. **uid:** `/etc/twitch.env` is `0600 streamd:streamd`. `claudeop` is
   not `streamd` and not in `streamd`'s group. Kernel returns `EACCES`
   on `open()`.
2. **srt deny:** even if uid permissions were lax, srt's filesystem
   profile explicitly denies `/etc/twitch.env`.
3. **/proc isolation:** `claudeop` cannot read
   `/proc/<streamd-pid>/environ` (the env file gets loaded into the
   daemon's environment) because `/proc/<pid>/environ` is mode
   `0400 streamd:streamd`. srt also blocks `/proc/[0-9]*` reads.
4. **ptrace:** even if `claudeop` somehow ran a debugger, kernel
   `ptrace_scope` + uid mismatch + srt's seccomp filter all block
   ptrace against streamd.
5. **Protocol:** the socket reply set is enumerated (`ok started`,
   `ok stopped`, `ok already-running`, `running pid=N uptime=Ns`,
   `stopped`). The key is never echoed.

A bypass would need to break at least three of these layers
simultaneously. None alone is enough.

### Threat model boundary

The "Claude can't see the key" claim is scoped to the **sandboxed
Claude as `claudeop`**. It is **not** a defense against an attacker
who already has shell access on the EC2 host:

- Anyone who can `cat /proc/<ffmpeg-pid>/cmdline` recovers the rtmp
  URL with the key embedded. We mitigate this for the sandboxed
  Claude by mounting `/proc` with `hidepid=invisible`, which makes
  other users' /proc entries invisible to non-root non-ffmpeg-user
  processes. But `root`, `streamd`, or anyone the operator gives sudo
  to can still read it.
- ffmpeg cannot read the rtmp URL from anywhere except argv (or env-
  via-shell-expansion). Keeping the secret out of argv would require
  a wrapper that holds an open stdin pipe, which neither ffmpeg nor
  any major rtmp client supports natively. A real fix is `nginx-rtmp`
  as a localhost RTMP relay so ffmpeg pushes to a keyless local URL
  and nginx adds the key — out of scope for v1.

Treat this as a known limit. The design protects against the
sandboxed Claude as the only adversary; full defense in depth against
arbitrary host-shell access is a follow-up.

## v1 deployment state (2026-04-25)

What works on the live g4dn.xlarge stack right now:
- All five systemd units active: xorg-headless, xauth-share,
  twitch-streamd, btone-bot, claude-tmux.
- Three-uid separation enforced. `claudeop` is denied:
  - `/etc/btone-stream/env` (kernel uid check; verified `EACCES`)
  - `/proc/<other-uid>/*` (mounted with `hidepid=invisible`; verified
    `ENOENT` for streamd's pid)
  - `169.254.169.254` IMDS (srt deniedDomains + the netns has no route)
- OUTSIDE the sandbox: `claudeop` can drive both surfaces correctly:
  - `curl 127.0.0.1:25591/health` → 401 (bridge alive, no auth token
    in plain curl — expected)
  - `/var/lib/btone/source/bin/twitch-stream STATUS` → `stopped`
- The `tmux` session + `claude-attach` + `claude-send` operator path
  exists and is wired up. OAuth flow not yet completed.

What does NOT work in v1 (deferred to v2):
- INSIDE the sandbox, `srt` still:
  - rejects `socket(AF_UNIX, SOCK_STREAM, 0)` with `EPERM` even with
    `allowUnixSockets` set, so `bin/twitch-stream` from inside the
    sandbox fails
  - puts the sandbox in a fresh netns where `127.0.0.1:25591` has no
    service, even with `enableWeakerNetworkIsolation: true`
- Net result: a sandboxed Claude can authenticate to api.anthropic.com,
  edit `/var/lib/btone/source/`, and push git, but cannot drive the
  bot bridge or trigger a stream from inside its sandbox.

### v2 paths (pick one)

a. **Domain-wrapper for bridge + streamd**: write a tiny localhost
   HTTP server bound to a hostname (`bot.local` in /etc/hosts) that
   exposes `/bridge/*` (proxies to 127.0.0.1:25591) and `/stream/*`
   (proxies to /run/twitch-streamd/sock). srt sees a normal HTTPS
   call to an allowlisted domain. ~50 lines.

b. **Switch to firejail**: firejail's local-IPC story is more
   permissive. Reuses the existing uid separation; just swaps the
   wrapper.

c. **Patch srt's profile schema**: if `allowUnixSockets` is supposed
   to seccomp-allow AF_UNIX `socket()`, this is an upstream bug. File
   it, ship a workaround in the meantime.

The security boundary (key isolation, IMDS deny, /proc isolation, FS
allowlist) is independent of the v2 functional fixes — it's working
now and the v2 work doesn't weaken it.

## What this design doesn't fix

- **Claude can grief the bot.** It has full RPC access to MC via the
  bridge. Acceptable — that's the whole point. A future iteration
  could add a method allowlist on the bridge side.
- **Claude can `git push` to your repo.** It has `github.com` egress
  and read+write to `/var/lib/btone/source/`. Acceptable for a single
  trusted operator; would need a fine-grained PAT scoped to a single
  repo if the threat model widens.
- **Claude can fill the disk.** No quota enforcement. The 30 GB gp3
  root volume is the only ceiling.
- **Claude can spin its own subprocess that's not sandboxed by srt.**
  Actually false — `srt` uses bubblewrap which propagates the seccomp
  filter to children. Children inherit the sandbox.
- **No automatic kernel pinning revisit.** The 6.8 kernel pin lives in
  `setup-ubuntu.sh`; when Ubuntu archives that package, the deploy
  breaks. Same caveat as the existing skill.

## Implementation order

1. Add the three users (`claudeop`, `streamd`, group `streamcontrol`)
   to `setup-ubuntu.sh`.
2. Write `/usr/local/bin/twitch-streamd` (Python) +
   `twitch-streamd.service` unit.
3. Write the `srt` profile JSON. Test it manually first by running
   `srt --settings /etc/claude/srt-profile.json -- bash` and trying
   the things Claude should and shouldn't be able to do.
4. Write `claude-tmux.service` + `claude-launch.sh` +
   `claude-attach` wrapper + sudoers fragment.
5. `npm install -g @anthropic-ai/sandbox-runtime` and Claude Code CLI
   in the setup script (Claude Code is `npm i -g @anthropic-ai/claude-code`).
6. Wire `bin/twitch-stream` into the repo so Claude inside the
   sandbox can call it.
7. End-to-end test: `claude-attach`, ask Claude to start a stream,
   verify Twitch shows live within 30s, ask for `player.state`,
   verify expected numbers.
