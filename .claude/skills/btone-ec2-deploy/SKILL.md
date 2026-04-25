---
name: btone-ec2-deploy
description: Use when the user wants to deploy btone-mod-c to a headless AWS EC2 instance. Covers initial provisioning via CloudFormation, Ubuntu 24.04 cloud-init setup with Nvidia GPU + headless Xorg, mod jar built on the instance, the SSH tunnel that makes bin/btone-cli reach the remote bridge, and tear-down. Triggers — "deploy to ec2", "headless minecraft", "run the bot in the cloud", "provision a remote bot".
---

# btone-ec2-deploy

Headless btone-mod-c on a `g4dn.xlarge` Ubuntu 24.04 instance. Real Tesla T4 GPU
under a custom headless Xorg. Bridge port stays on the instance's `127.0.0.1`
and is reached from the operator's laptop via SSH tunnel.

```
operator laptop ── SSH(22) ── EC2 g4dn.xlarge (Ubuntu 24.04)
       │                        │
       │ ssh -L 25592             │ Xorg :99 ◀── MC client (Tesla T4)
       │ tunnel                   │                    │
       ▼                          ▼                    ▼
 bin/btone-cli ── HTTP ── 127.0.0.1:25591 (bridge) + btone-mod-c
```

Three artifacts:

| File | Role |
|---|---|
| `infra/btone-ec2.yaml` | CloudFormation: SG, EIP, EC2 g4dn.xlarge, Ubuntu AMI |
| `infra/setup-ubuntu.sh` | cloud-init script: kernel pin, nvidia driver, headless Xorg, mod build, systemd units |
| `bin/btone-ec2.sh` | wrapper for the AWS CLI + ssh sequence |

The mod jar is **built on the instance** during setup (gradle, no scp). Iterate
by `git push` to origin, then `bin/btone-ec2.sh push` rebuilds + restarts.

## Prerequisites

```bash
# AWS CLI configured.
aws sts get-caller-identity

# A key pair uploaded; matching private key at ~/.ssh/<name>.pem (or
# ~/.ssh/id_ed25519 — the wrapper accepts KEY_FILE override).
aws ec2 import-key-pair --region us-west-2 \
  --key-name btone --public-key-material "$(cat ~/.ssh/id_ed25519.pub | base64)"
```

Wrapper env (defaults shown):

```bash
export AWS_REGION=us-west-2
export KEY_NAME=btone
export KEY_FILE=~/.ssh/id_ed25519
export INSTANCE_TYPE=g4dn.xlarge   # only g4dn/g6 are supported — software GL is unusably slow for MC 1.21
export STACK_NAME=btone-ec2
export BRIDGE_PORT=25592           # local tunnel port; default avoids the laptop-side bot's 25591
```

## 1. Provision

```bash
bin/btone-ec2.sh provision
```

CloudFormation deploys: SG (SSH from your /32), EIP, EC2 with the latest
Ubuntu 24.04 AMI (resolved via Canonical's public SSM parameter — no AMI
lookup gymnastics).

## 2. Setup (apt + nvidia + mod build + reboot)

```bash
bin/btone-ec2.sh setup
```

Runs `setup-ubuntu.sh` on the instance, which does ALL of:

1. Installs `linux-image-6.8.0-1008-aws` + headers (the AMI's default
   `6.17` kernel drops `drm_fbdev_ttm_driver_fbdev_probe`, breaking
   `nvidia_drm`).
2. Installs `nvidia-driver-590` (DKMS-built against 6.8).
3. Sets `nvidia-drm.modeset=1` via `/etc/modprobe.d/nvidia.conf`.
4. Loads `snd-dummy` (virtual ALSA device — without it, MC's OpenAL
   init SIGABRTs the JVM).
5. Pre-writes `/var/lib/btone/options.txt` with `onboardAccessibility:false`
   so MC skips the "Welcome / Accessibility" first-launch screen
   (otherwise `--quickPlayMultiplayer` blocks behind it forever).
6. Installs OpenJDK 21, Python 3, portablemc.
7. `git clone`s this repo to `/var/lib/btone/source` and runs
   `./gradlew build` — produces `btone-mod-c-0.1.0.jar`.
8. Downloads fabric-api, fabric-language-kotlin, meteor-client,
   baritone-api-fabric (Sodium intentionally **omitted** — its shader
   path crashed software GL during testing).
9. Writes a custom `xorg-headless.conf` (AutoAddGPU=false, BusID
   pinned to the GPU's PCI slot, AllowEmptyInitialConfiguration), and a
   `xorg-headless.service` unit that runs `Xorg :99` with the right
   `-modulepath` for the nvidia X driver.
10. Writes the `btone-bot.service` unit; an env file with
    `BOT_USERNAME=BotEC2 BOT_SERVER_HOST=...`.
11. Reboots into the 6.8 kernel — wrapper waits for SSH to come back,
    then starts `btone-bot`.

Total time: 5–10 min.

## 3. Tunnel + verify

```bash
bin/btone-ec2.sh tunnel    # opens ssh -fN -L 25592:127.0.0.1:25591
bin/btone-cli player.state # via the local config the wrapper just dropped
```

Expect:
```json
{"ok":true,"result":{"inWorld":true,"name":"BotEC2","pos":{"x":...,"y":...,"z":...},"hp":20,"food":20}}
```

If `inWorld:false` for >2 minutes, MC may be stuck on a screen — see *Common
failure modes* below.

## 4. Iterate on mod-c

Commit local changes → push to `origin/master` → run:

```bash
bin/btone-ec2.sh push
```

This `git pull`s on the instance, runs `./gradlew build` (incremental, ~30s),
copies the jar into place, and restarts `btone-bot`. The wrapper refuses if
`mod-c/` has uncommitted changes (git pull can't see them).

## 5. Operations

| Task | Command |
|---|---|
| Tail bot logs | `bin/btone-ec2.sh logs` |
| Restart MC after a crash | `bin/btone-ec2.sh restart` |
| Re-deploy after mod-c change | commit + push + `bin/btone-ec2.sh push` |
| Re-open tunnel after laptop sleep | `bin/btone-ec2.sh tunnel` |
| SSH for poking around | `bin/btone-ec2.sh ssh` |
| Tear it all down | `bin/btone-ec2.sh destroy` |

## 6. Cost

| Pattern | Approx |
|---|---|
| 24/7 on-demand | $384/mo |
| 8 hr/day | $93/mo |
| Spin up / `destroy` per session | $0.526/hr |

Plus 30 GB gp3: **$2.40/mo** (charged regardless of running state).
EIP is free while attached. `bin/btone-ec2.sh destroy` is the off switch.

## 7. Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `provision` fails: `KeyName not found` | Key pair not in the region | `aws ec2 import-key-pair --region $AWS_REGION ...` |
| Stack fails: `Value (...) for parameter GroupDescription is invalid` | Em-dash or non-ASCII in description | Use plain hyphens — already fixed in template |
| `setup` errors `Could not get lock /var/lib/dpkg/lock-frontend` | Boot-time `unattended-upgrades` running | Wait 1-2 min, re-run `setup` |
| `nvidia_drm: Unknown symbol drm_fbdev_ttm_driver_fbdev_probe` | Kernel 6.17 incompatible with public nvidia drivers | Setup pins to 6.8 — confirm `uname -r` is `6.8.0-1008-aws` after reboot |
| `glxinfo` shows `llvmpipe`, not Tesla T4 | Xorg using modesetting instead of nvidia | Check `cat /etc/X11/xorg-headless.conf` — `BusID` must be decimal `PCI:0:30:0`, not hex `00000000:00:1E.0` |
| Bot alive but `inWorld:false` for >2 min | MC stuck on Welcome/Accessibility screen | Confirm `/var/lib/btone/options.txt` contains `onboardAccessibility:false` and restart bot |
| `Failed to open OpenAL device` then SIGABRT | snd-dummy not loaded | `lsmod \| grep snd_dummy` — should be present; re-`modprobe snd_dummy` if missing |
| Tunnel: `bind 127.0.0.1:25592: Address already in use` | Old tunnel still alive | `pkill -f "ssh.*-L $BRIDGE_PORT" && bin/btone-ec2.sh tunnel` |
| `bin/btone-cli` returns the laptop bot, not EC2 | Conflicting bridge configs | Use a different `BRIDGE_PORT` (default 25592) — wrapper rewrites the local config to match |
| `setup` fails: `linux-image-6.8.0-1008-aws not found` | Ubuntu archived the package | Update setup-ubuntu.sh to a newer `linux-image-*.0-aws` version that nvidia 590 still supports — check `apt-cache search '^linux-image-.*-aws$'` for what's available |

## 8. What this skill does NOT do

- **No backups** of `/var/lib/btone`. Inventory, world progress, and
  the bot's bridge token live on the EBS volume. Snapshot manually if needed.
- **No spot instances** — the bot uses persistent state and would lose
  it on reclaim. On-demand only.
- **No automated cost guardrails.** `bin/btone-ec2.sh destroy` is the kill switch.
- **No CPU-only fallback.** Mesa software GL renders MC's title screen at
  <1 fps — the bot never reaches the multiplayer connect. GPU is required.
- **No automatic kernel pin maintenance.** When Ubuntu deprecates the
  6.8.0-1008-aws kernel, setup-ubuntu.sh's KERNEL_VERSION needs updating.
