---
name: btone-ec2-deploy
description: Use when the user wants to deploy btone-mod-c to a headless AWS EC2 instance (no GPU, software-rendered Minecraft under Xvfb + Mesa llvmpipe). Covers initial provisioning via CloudFormation, NixOS configuration over SSH, mod jar upload, the SSH tunnel that makes bin/btone-cli reach the remote bridge, and tear-down. Triggers — "deploy to ec2", "headless minecraft", "run the bot in the cloud", "provision a remote bot".
---

# btone-ec2-deploy

The bot is *not* headless by design — it drives a real rendered MC client.
This skill makes that work on a non-GPU EC2 box by running the client
under **Xvfb** with **Mesa llvmpipe** software rendering. Expect 5–15 fps
inside the framebuffer; that's enough for the bot, not for a human.

```
operator laptop  ─SSH(22)─▶  EC2 (NixOS, no GPU)
        │                         │
        │ ssh -L 25591             │ Xvfb :99 ◀── MC client (LLVMpipe)
        │ tunnel                   │                 │
        ▼                          ▼                 ▼
  bin/btone-cli  ─HTTP─▶  127.0.0.1:25591 (bridge)  + btone-mod-c
```

Three artifacts in this repo do the work:

| File | Role |
|---|---|
| `infra/btone-ec2.yaml` | CloudFormation: SG, EIP, EC2 instance from a NixOS AMI |
| `infra/nixos.nix` | NixOS module: Xvfb, llvmpipe env, btone user, systemd units |
| `bin/btone-ec2.sh` | Thin wrapper for the AWS CLI + ssh + rsync commands below |

The wrapper is the happy path. Every step also has a copy-paste raw form
in this runbook for when something goes wrong and you need to drop down.

## Prerequisites

```bash
# AWS CLI configured with creds that can run CloudFormation + EC2.
aws sts get-caller-identity   # should return your account

# A key pair uploaded to AWS in your region. If you don't have one:
aws ec2 import-key-pair --region "$AWS_REGION" \
  --key-name btone --public-key-material "$(cat ~/.ssh/id_ed25519.pub)"
# Then keep the matching private key at ~/.ssh/btone.pem (chmod 600).

# Mod jar built locally — the wrapper rejects deploys without one.
cd mod-c && nix develop .. --command ./gradlew build && cd ..
ls mod-c/build/libs/btone-mod-c-*.jar    # sanity check
```

The wrapper reads these env vars (all have defaults except `KEY_NAME`):

```bash
export AWS_REGION=us-east-1          # or whatever
export KEY_NAME=btone                # your AWS key pair name
export KEY_FILE=~/.ssh/btone.pem     # default: ~/.ssh/$KEY_NAME.pem
export INSTANCE_TYPE=t3.large        # llvmpipe is CPU-bound; bump to xlarge if MC OOMs
export STACK_NAME=btone-ec2          # cfn stack; one per bot
```

## 1. Look up the NixOS AMI

```bash
bin/btone-ec2.sh ami
# → ami-0abc...   (NixOS 24.11 x86_64, latest by CreationDate)
```

Raw form (the wrapper does the same thing):

```bash
aws ec2 describe-images --region "$AWS_REGION" --owners 427812963091 \
  --filters "Name=name,Values=nixos/*" \
            "Name=architecture,Values=x86_64" \
            "Name=virtualization-type,Values=hvm" \
            "Name=root-device-type,Values=ebs" \
  --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text
```

The naming scheme is `nixos/<version>.<rev>.<commit>-<arch>-linux` (lowercase,
slash — *not* the older `NixOS-24.11*` pattern, which only matches deprecated
images from before 2023). Owner `427812963091` is the official NixOS publishing
account and pushes weekly. AMIs older than 90 days get GC'd, so always resolve
the latest dynamically — no hardcoded IDs. AMI table also viewable at
<https://nixos.github.io/amis/>.

## 2. Provision the stack

```bash
bin/btone-ec2.sh provision
# logs the AMI ID, your /32, then runs aws cloudformation deploy.
# Stack creation takes ~3 minutes (most of which is EC2 boot).
```

Raw form:

```bash
aws cloudformation deploy --region "$AWS_REGION" \
  --stack-name btone-ec2 \
  --template-file infra/btone-ec2.yaml \
  --parameter-overrides \
      KeyName="$KEY_NAME" \
      InstanceType=t3.large \
      AllowedSshCidr="$(curl -s ifconfig.me)/32" \
      NixosAmiId="$(bin/btone-ec2.sh ami)"
```

Get the public IP after it's done:

```bash
bin/btone-ec2.sh ip
```

## 3. First SSH-in

NixOS AMIs use **`root`** as the default SSH user, not `ec2-user`. The
key you uploaded as `KeyName` is what cloud-init plants in
`/root/.ssh/authorized_keys`.

```bash
bin/btone-ec2.sh ssh                    # interactive
bin/btone-ec2.sh ssh -- 'uname -a'      # one-shot
```

Confirm the instance is a stock NixOS box at this point — `btone-bot.service`
hasn't been activated yet because the flake hasn't been pushed.

## 4. Push the flake + mod jar

```bash
bin/btone-ec2.sh push
# rsyncs flake.nix, flake.lock, infra/ → /etc/nixos/btone/
# scp's mod-c/build/libs/btone-mod-c-*.jar → /var/lib/btone/mods/btone-mod-c-0.1.0.jar
# chowns /var/lib/btone to btone:btone
```

Re-run `push` whenever you rebuild the mod or change `infra/nixos.nix`.

## 5. Activate the NixOS config

```bash
bin/btone-ec2.sh rebuild
# remote: nixos-rebuild switch --flake /etc/nixos/btone#btone-ec2
```

First run takes 3–5 minutes (downloads JDK 21, Mesa, Xorg, portablemc,
all the GL stack). Subsequent rebuilds are fast.

When this returns, `xvfb.service` is up, `btone-mods-bootstrap.service`
has fetched fabric-api / kotlin / meteor / baritone / sodium into
`/var/lib/btone/mods/`, and `btone-bot.service` has launched portablemc.
portablemc itself takes another minute or two on its first launch
(downloads MC 1.21.8 + Fabric loader 0.19.2). Watch:

```bash
bin/btone-ec2.sh logs    # journalctl -fu btone-bot
```

You're looking for:

```
btone-mod-c listening on 127.0.0.1:25591; config at .../btone-bridge.json
```

## 6. Tunnel the bridge to your laptop

The bridge listens on `127.0.0.1` only — there's no public port. The
wrapper opens a backgrounded SSH tunnel and pulls the bridge config to
the local `~/btone-mc-work/config/btone-bridge.json` so `bin/btone-cli`
just works.

```bash
bin/btone-ec2.sh tunnel
# pkill any existing tunnel, scp the bridge config, ssh -fN -L 25591:127.0.0.1:25591
```

Raw form:

```bash
IP=$(bin/btone-ec2.sh ip)
scp -i "$KEY_FILE" "root@$IP:/var/lib/btone/.minecraft/config/btone-bridge.json" \
    ~/btone-mc-work/config/btone-bridge.json
ssh -i "$KEY_FILE" -fN -L 25591:127.0.0.1:25591 "root@$IP"
```

## 7. Verify

```bash
bin/btone-cli player.state | jq -c '{inWorld,pos:.blockPos,hp:.health}'
# → {"inWorld":true,"pos":{"x":498,"y":69,"z":919},"hp":20}
```

If you get connection-refused, the tunnel died — re-run step 6.
If `inWorld:false`, MC hasn't auto-connected to the server yet
(LLVMpipe-rendered title screen → server-list → connect takes ~30s
longer than on a real GPU). Wait, then retry.

## 8. Day-to-day operations

| Task | Command |
|---|---|
| Tail bot logs | `bin/btone-ec2.sh logs` |
| Restart MC after a crash | `bin/btone-ec2.sh restart` |
| Redeploy after `mod-c` change | `cd mod-c && ./gradlew build && cd .. && bin/btone-ec2.sh push && bin/btone-ec2.sh restart` |
| Re-apply NixOS config change | `bin/btone-ec2.sh push && bin/btone-ec2.sh rebuild` |
| Re-open tunnel after laptop sleep | `bin/btone-ec2.sh tunnel` |
| SSH for poking around | `bin/btone-ec2.sh ssh` |
| Tear it all down | `bin/btone-ec2.sh destroy` |

A `nixos-rebuild switch` does **not** restart `btone-bot.service` unless
the unit's `ExecStart` actually changed — bump the unit (or just call
`restart`) when you only updated the jar.

## 9. Common failure modes

| Symptom | Cause | Fix |
|---|---|---|
| `ami` returns empty / `None` | Wrong name filter (e.g. `NixOS-24.11*` — those are deprecated 2023 images) | Use `nixos/*` (lowercase, slash). Confirmed working in us-east-1, us-east-2, us-west-2, eu-* |
| `provision` errors `KeyName not found` | Key pair not in this region | `aws ec2 import-key-pair --region ...` first |
| Stack `CREATE_FAILED: SG ingress invalid CIDR` | Behind a NAT that returned IPv6 to ifconfig.me | `ALLOWED_SSH_CIDR=x.x.x.x/32 bin/btone-ec2.sh provision` |
| `ssh: Permission denied (publickey)` | Wrong default user | NixOS uses **`root`**, not `ec2-user`/`admin` |
| `rebuild` fails: "experimental feature 'flakes' disabled" | Stock NixOS AMIs sometimes ship without flakes pre-enabled | `ssh root@$IP 'mkdir -p /etc/nix && echo "experimental-features = nix-command flakes" >> /etc/nix/nix.conf'` then retry |
| `btone-bot.service` exits 1 immediately | mod jar missing — `ExecStartPre` `test -f` failed | Re-run `bin/btone-ec2.sh push` |
| `btone-bot.service` runs but no `listening on 127.0.0.1` | Xvfb not running, or LLVMpipe couldn't init OpenGL 3.3 | `ssh root@$IP 'systemctl status xvfb && DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 glxinfo \| head'` |
| Java logs `Pixel format not accelerated` | Missing `LIBGL_ALWAYS_SOFTWARE=1` (env not inherited) | Confirm with `systemctl show btone-bot -p Environment` |
| MC saturates 100% CPU and times out from server | llvmpipe at default settings | Bump `INSTANCE_TYPE=c6i.xlarge`, or lower view distance — already 6 chunks via `BtoneC.java:133` |
| Tunnel reconnects fail with "address already in use" | Old tunnel still alive | `pkill -f 'ssh.*-L 25591'` then retry |
| Bridge config has port ≠ 25591 | Port was busy when MC booted; mod fell back | The wrapper handles this — local tunnel is always 25591, remote port is rewritten in the local config |
| `journalctl -u btone-bot` shows OOM-killer | Default `t3.large` 8GB is borderline with MC + llvmpipe + Java heap | `INSTANCE_TYPE=t3.xlarge` and `bin/btone-ec2.sh provision` again |

## 10. What this skill does NOT do

- **No backups** of `/var/lib/btone`. Login session, custom Meteor module
  state, `coords.md`-equivalent in-world progress are all on the EBS volume.
  Snapshot it manually if it matters: `aws ec2 create-snapshot`.
- **No auto-stop / cost guardrails.** A `t3.large` running 24/7 is ~$60/mo
  in `us-east-1`. `bin/btone-ec2.sh destroy` is the off-switch.
- **No multi-region / multi-instance state.** One stack = one bot. Run
  multiple stacks (different `STACK_NAME`) for multiple bots; each will
  want its own local `BRIDGE_PORT` to avoid tunnel collisions.
- **No build-on-remote of the mod.** The mod is built locally and `scp`'d.
  Building it inside the Nix sandbox would mean writing a `gradle2nix`-
  style fixed-output derivation — non-trivial because Gradle wants
  network access. Out of scope for this skill.
- **No real GPU.** If you need >15fps (e.g. for video capture), this is
  the wrong deploy target — switch to a `g4dn.xlarge` or similar and
  install proper Nvidia drivers. This skill assumes the bot is the only
  consumer of the rendered framebuffer.
