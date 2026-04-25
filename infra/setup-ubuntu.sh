#!/bin/bash
# Cloud-init UserData for the Ubuntu btone-mod-c bot host on g4dn.xlarge.
#
# Runs once at first boot as root. Installs:
#   - Older Linux kernel (6.8) — kernel 6.17 (the AMI default in 04/2026)
#     drops drm_fbdev_ttm_driver_fbdev_probe which the public nvidia
#     drivers (570, 590) still expect; nvidia_drm fails to load.
#   - nvidia-driver-590
#   - headless Xorg with Nvidia (custom xorg.conf, AutoAddGPU=false,
#     proper BusID, AllowEmptyInitialConfiguration)
#   - Java 21, gradle (via the project's wrapper), portablemc, mods
#   - snd-dummy ALSA device — without it MC's OpenAL init SIGABRTs
#   - options.txt seeded with onboardAccessibility:false — without it
#     MC blocks on the Welcome/Accessibility dialog and never reaches
#     --quickPlayMultiplayer
#
# After running, REBOOT REQUIRED to load the 6.8 kernel + nvidia DRM
# module. The wrapper handles that.
set -euxo pipefail

MC_VERSION="1.21.8"
FABRIC_LOADER="0.19.2"
BTONE_HOME=/var/lib/btone
KERNEL_VERSION="6.8.0-1008-aws"
NVIDIA_DRIVER="nvidia-driver-590"

# --- 1. apt deps -------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends \
  "linux-image-${KERNEL_VERSION}" "linux-headers-${KERNEL_VERSION}" \
  "$NVIDIA_DRIVER" \
  openjdk-21-jre-headless openjdk-21-jdk-headless \
  python3 python3-pip python3-venv \
  jq curl ca-certificates git \
  xserver-xorg-core xinit xauth x11-xserver-utils \
  pulseaudio pulseaudio-utils alsa-utils \
  mesa-utils \
  ffmpeg \
  bubblewrap socat ripgrep tmux \
  xxd

# --- 2. pin grub default to the older kernel --------------------------------
sed -i "s|^GRUB_DEFAULT=.*|GRUB_DEFAULT=\"Advanced options for Ubuntu>Ubuntu, with Linux ${KERNEL_VERSION}\"|" /etc/default/grub
update-grub

# --- 3. dummy audio device (kernel-level ALSA) ------------------------------
echo snd-dummy >/etc/modules-load.d/snd-dummy.conf

# --- 4. nvidia DRM modeset --------------------------------------------------
# nvidia_drm needs modeset=1 to expose /dev/dri/card1 (the GPU's DRM node).
echo "options nvidia-drm modeset=1 fbdev=1" >/etc/modprobe.d/nvidia.conf

# --- 5. btone user + dirs ---------------------------------------------------
if ! id btone >/dev/null 2>&1; then
  useradd -m -d "$BTONE_HOME" -s /bin/bash btone
fi
install -d -o btone -g btone -m 0755 "$BTONE_HOME/mods"
install -d -o btone -g btone -m 0755 "$BTONE_HOME/config"
usermod -aG audio,video btone

# Allow other users (claudeop) to traverse btone's home dir to reach
# /var/lib/btone/source (the codebase). 0751 = btone has full rwx,
# btone group has rx, others have x only — i.e. anyone can `cd` into
# subpaths but cannot list the directory itself. Inside, files keep
# their per-file permissions (mode 0644 source files = readable;
# bridge config keeps the token but claudeop legitimately needs it).
chmod 0751 "$BTONE_HOME"

# --- 6. options.txt: skip the Welcome/Accessibility dialog ------------------
# MC 1.21+ shows AccessibilityOnboardingScreen on first launch, blocking
# --quickPlayMultiplayer until "Continue" is clicked. Pre-seed the option
# that marks onboarding done.
cat >"$BTONE_HOME/options.txt" <<'EOF'
onboardAccessibility:false
narrator:0
forceUnicodeFont:false
tutorialStep:none
EOF
chown btone:btone "$BTONE_HOME/options.txt"

# --- 7. portablemc ----------------------------------------------------------
sudo -u btone python3 -m pip install --user --break-system-packages portablemc
PMC_BIN="$BTONE_HOME/.local/bin/portablemc"

# --- 8. clone source + build the mod jar on the instance --------------------
SOURCE_DIR="$BTONE_HOME/source"
REPO_URL="https://github.com/r33drichards/mca.git"

if [ ! -d "$SOURCE_DIR/.git" ]; then
  sudo -u btone git clone "$REPO_URL" "$SOURCE_DIR"
else
  sudo -u btone git -C "$SOURCE_DIR" fetch origin
  sudo -u btone git -C "$SOURCE_DIR" reset --hard origin/master
fi

sudo -u btone -H env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  bash -c "cd '$SOURCE_DIR/mod-c' && ./gradlew --no-daemon --console=plain build"

install -o btone -g btone -m 0644 \
  "$SOURCE_DIR/mod-c/build/libs/btone-mod-c-0.1.0.jar" \
  "$BTONE_HOME/mods/btone-mod-c-0.1.0.jar"

# --- 9. fetch non-btone mods (fabric-api, kotlin, meteor, baritone) ---------
fetch_modrinth() {
  local slug="$1"
  local url
  url=$(curl -sfL "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${MC_VERSION}%22%5D&loaders=%5B%22fabric%22%5D" \
    | jq -r '.[0].files[] | select(.primary == true) | .url' | head -n1)
  [ -n "$url" ] && [ "$url" != "null" ] || { echo "no $MC_VERSION fabric release for $slug" >&2; exit 1; }
  curl -sfL -o "$BTONE_HOME/mods/$(basename "$url")" "$url"
}

fetch_modrinth fabric-api
fetch_modrinth fabric-language-kotlin
# Sodium intentionally OMITTED — under software OpenGL its shader path
# segfaulted in lp_rast_shade_tile (Mesa llvmpipe). Vanilla MC's
# renderer is plenty fast on the T4 GPU at view distance 6.

curl -sfL -o "$BTONE_HOME/mods/meteor-client-${MC_VERSION}.jar" \
  "https://meteorclient.com/api/download?version=${MC_VERSION}"
curl -sfL -o "$BTONE_HOME/mods/baritone-api-fabric-1.15.0.jar" \
  "https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar"

chown -R btone:btone "$BTONE_HOME/mods"

# --- 10. headless Xorg config (Nvidia GPU, no monitor) ----------------------
# BusID has to be in DECIMAL Xorg format ("PCI:bus:device:function").
# nvidia-smi reports HEX ("00000000:00:1E.0"), so we convert. On g4dn
# the GPU lives at PCI 0:30:0; falling back to that is safe.
NVIDIA_BUSID="PCI:0:30:0"
if command -v nvidia-smi >/dev/null 2>&1; then
  raw=$(nvidia-smi --query-gpu=pci.bus_id --format=csv,noheader 2>/dev/null | head -1 || true)
  if [ -n "$raw" ]; then
    NVIDIA_BUSID=$(awk -F'[:.]' '{
      bus = strtonum("0x" $2);
      dev = strtonum("0x" $3);
      fn  = strtonum("0x" $4);
      printf "PCI:%d:%d:%d", bus, dev, fn
    }' <<<"$raw")
  fi
fi
echo "Nvidia BusID for Xorg: $NVIDIA_BUSID"

cat >/etc/X11/xorg-headless.conf <<EOF
# AutoAddGPU=false stops Xorg from picking up /dev/dri/card0 (the AWS
# simple-framebuffer) and loading modesetting on top of it — without
# this, Xorg would ignore our nvidia Device section.
Section "ServerFlags"
  Option "AutoAddGPU" "false"
  Option "AutoBindGPU" "false"
EndSection

Section "ServerLayout"
  Identifier "Layout0"
  Screen 0 "Screen0" 0 0
EndSection

Section "Device"
  Identifier "Device0"
  Driver     "nvidia"
  VendorName "NVIDIA Corporation"
  BusID      "$NVIDIA_BUSID"
  Option     "AllowEmptyInitialConfiguration" "true"
EndSection

Section "Monitor"
  Identifier "Monitor0"
  HorizSync   28.0 - 80.0
  VertRefresh 48.0 - 75.0
  ModeLine    "1280x720" 74.48 1280 1336 1472 1664 720 721 724 746 -HSync +Vsync
  Option      "DPMS"
EndSection

Section "Screen"
  Identifier "Screen0"
  Device     "Device0"
  Monitor    "Monitor0"
  DefaultDepth 24
  SubSection "Display"
    Depth 24
    Modes "1280x720"
  EndSubSection
EndSection
EOF

# --- 11. systemd: headless Xorg ---------------------------------------------
# /usr/lib/x86_64-linux-gnu/nvidia/xorg/ holds nvidia_drv.so on Ubuntu.
# Without -modulepath including it, Xorg's default ModulePath sees only
# modesetting and fails to load the nvidia driver.
cat >/etc/systemd/system/xorg-headless.service <<'EOF'
[Unit]
Description=Headless Xorg server (Nvidia GPU) for the btone bot
After=network-online.target systemd-modules-load.service
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=/usr/bin/Xorg :99 -modulepath /usr/lib/x86_64-linux-gnu/nvidia/xorg,/usr/lib/xorg/modules -config /etc/X11/xorg-headless.conf -nolisten tcp -noreset -logfile /var/log/xorg-headless.log
Restart=always
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF

# --- 12. systemd: btone-bot -------------------------------------------------
cat >/etc/systemd/system/btone-bot.service <<EOF
[Unit]
Description=btone-mod-c Minecraft bot (headless, GPU-rendered)
After=xorg-headless.service network-online.target sound.target
Requires=xorg-headless.service
Wants=network-online.target

[Service]
Type=simple
User=btone
Group=btone
WorkingDirectory=$BTONE_HOME
EnvironmentFile=-/etc/btone-bot.env
Environment=DISPLAY=:99
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ExecStartPre=/usr/bin/test -f $BTONE_HOME/mods/btone-mod-c-0.1.0.jar
ExecStart=$PMC_BIN --work-dir $BTONE_HOME start fabric:$MC_VERSION:$FABRIC_LOADER \\
  --jvm /usr/bin/java --auth-anonymize \\
  -u \${BOT_USERNAME} -s \${BOT_SERVER_HOST} -p \${BOT_SERVER_PORT}
Restart=on-failure
RestartSec=15s
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

cat >/etc/btone-bot.env <<'EOF'
BOT_USERNAME=BotEC2
BOT_SERVER_HOST=centerbeam.proxy.rlwy.net
BOT_SERVER_PORT=40387
EOF

# --- 12.5 streaming infra (Twitch via ffmpeg, key-isolated under streamd) --
# Three users own three concerns:
#   btone     — runs MC + the RPC bridge (existing)
#   streamd   — owns /etc/btone-stream/env (the Twitch key) + the ffmpeg
#               subprocess. No login shell. Members of `streamcontrol`
#               group can ask it to start/stop streams via the unix socket
#               at /run/twitch-streamd.sock — but cannot read the key.
#   claudeop  — added in §12.6; member of streamcontrol so the sandboxed
#               Claude can call bin/twitch-stream START.
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
fi
chown streamd:streamd /etc/btone-stream/env
chmod 0600 /etc/btone-stream/env

# X11 cookie share — Xorg started by root only allows root to connect.
# We drop a group-readable xauth cookie at a path streamd can read so
# ffmpeg (under streamd) can open DISPLAY=:99. xauth-share runs once
# after xorg-headless starts and stamps a fresh random cookie.
cat >/etc/systemd/system/xauth-share.service <<'EOF'
[Unit]
Description=Stamp a shared xauth cookie for streamd to read DISPLAY=:99
After=xorg-headless.service
PartOf=xorg-headless.service

[Service]
Type=oneshot
RemainAfterExit=yes
# Wait up to 30s for Xorg to bind :99 before stamping the cookie.
ExecStartPre=/bin/sh -c 'for i in $(seq 1 30); do [ -e /tmp/.X11-unix/X99 ] && exit 0; sleep 1; done; exit 1'
ExecStart=/usr/bin/install -m 0640 -o root -g streamd /dev/null /var/lib/twitch-streamd/.Xauthority
ExecStart=/bin/sh -c 'XAUTHORITY=/var/lib/twitch-streamd/.Xauthority /usr/bin/xauth add :99 . $(/usr/bin/xxd -l 16 -p /dev/urandom)'
ExecStart=/bin/sh -c 'DISPLAY=:99 /usr/bin/xhost +SI:localuser:streamd >/dev/null'

[Install]
WantedBy=xorg-headless.service
EOF

# Drop any prior drop-in that ExecStartPost'd xauth-share; the previous
# attempt deadlocked (Xorg waited for systemctl restart xauth-share to
# complete, xauth-share BindsTo'd Xorg → never reached active → never
# returned). Plain After+PartOf+WantedBy is enough for v1: WantedBy
# pulls xauth-share up when xorg-headless starts at boot, PartOf
# propagates stop. Manual `systemctl restart xorg-headless` won't
# auto-restart xauth-share — operator runs both. Documented as a known
# limit; revisit with a path-trigger if it bites.
rm -rf /etc/systemd/system/xorg-headless.service.d/restart-xauth.conf
rmdir /etc/systemd/system/xorg-headless.service.d 2>/dev/null || true

# Daemon owns ffmpeg lifecycle + the unix socket. No env override —
# STREAM_KEY comes only via EnvironmentFile, which only `streamd` and
# root can read.
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
SupplementaryGroups=video streamcontrol
# RuntimeDirectory= creates /run/twitch-streamd/ owned by User:Group at
# mode 0755, automatically removed on stop. The daemon's unix socket
# lives there as /run/twitch-streamd/sock — streamd writes it, group
# `streamcontrol` can connect (the daemon chmods 0660 streamd:streamcontrol).
RuntimeDirectory=twitch-streamd
RuntimeDirectoryMode=0755
EnvironmentFile=/etc/btone-stream/env
Environment=DISPLAY=:99
Environment=XAUTHORITY=/var/lib/twitch-streamd/.Xauthority
ExecStart=/usr/local/bin/twitch-streamd
ProtectSystem=strict
ReadWritePaths=/var/lib/twitch-streamd
ProtectHome=true
PrivateTmp=false
NoNewPrivileges=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
Restart=on-failure
RestartSec=5s
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# btone-stream.service from earlier scaffolding is superseded —
# twitch-streamd is the single owner of ffmpeg now.
systemctl disable --now btone-stream.service 2>/dev/null || true
rm -f /etc/systemd/system/btone-stream.service

# Disable any previous xvfb unit (if upgrading from CPU deploy).
systemctl disable --now xvfb.service 2>/dev/null || true
rm -f /etc/systemd/system/xvfb.service

systemctl daemon-reload
systemctl enable xorg-headless.service
systemctl enable xauth-share.service
systemctl enable btone-bot.service
systemctl enable twitch-streamd.service

# --- 12.6 claudeop user (sandboxed Claude Code runs as this) ---------------
# Has /bin/bash so tmux can give it a real shell. Member of streamcontrol
# so it can talk to /run/twitch-streamd.sock.
if ! id claudeop >/dev/null 2>&1; then
  useradd -m -s /bin/bash -d /home/claudeop claudeop
fi
usermod -aG streamcontrol claudeop
install -d -o claudeop -g claudeop -m 0755 /home/claudeop

# Hide /proc entries owned by other users — this is what actually
# protects the Twitch key (which appears in ffmpeg's argv via
# /proc/<pid>/cmdline) from any non-root non-streamd uid, including
# claudeop. srt's filesystem deny rules layer on top of this; this
# remount is the kernel-level guarantee.
if ! grep -q 'hidepid=invisible' /etc/fstab; then
  echo 'proc /proc proc defaults,hidepid=invisible 0 0' >> /etc/fstab
fi
mount -o remount,hidepid=invisible /proc 2>/dev/null || true

# --- 12.7 node + sandbox-runtime + claude code ------------------------------
# Ubuntu 24.04 ships Node 18, which @anthropic-ai/claude-code requires
# (>=18). Use NodeSource for Node 20 to be safe.
if ! command -v node >/dev/null 2>&1 || [ "$(node -v 2>/dev/null | sed 's/v\([0-9]*\).*/\1/')" -lt 20 ]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y --no-install-recommends nodejs
fi

# srt = sandbox-runtime CLI; claude = Claude Code CLI. Both npm globals.
# Re-runs are no-ops once present.
if ! command -v srt >/dev/null 2>&1; then
  npm install -g @anthropic-ai/sandbox-runtime
fi
if ! command -v claude >/dev/null 2>&1; then
  npm install -g @anthropic-ai/claude-code
fi

# --- 12.8 srt sandbox profile ---------------------------------------------
install -d -o root -g root -m 0755 /etc/claude
install -m 0644 -o root -g root \
  "/var/lib/btone/source/infra/srt-profile.json" \
  /etc/claude/srt-profile.json

# --- 12.9 claude-tmux service + operator attach helper ---------------------
# claude-launch.sh runs inside the tmux pty, drops into `srt -- claude`.
# tmux itself runs OUTSIDE the sandbox (as claudeop) but every child
# inherits the bubblewrap isolation.
install -m 0755 -o root -g root \
  "/var/lib/btone/source/infra/claude-launch.sh" \
  /usr/local/bin/claude-launch.sh

# Logfile for headless debugging — tmux pipe-pane writes everything the
# session prints here, owned by claudeop so the pipe can write.
install -m 0644 -o claudeop -g claudeop /dev/null /var/log/claude-session.log

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

# Attach helper (runs as ubuntu, sudo's into claudeop's tmux). Sudoers
# fragment grants exactly the four tmux subcommands ubuntu may issue
# against claudeop — no general sudo to that uid.
cat >/usr/local/bin/claude-attach <<'EOF'
#!/usr/bin/env bash
exec sudo -u claudeop /usr/bin/tmux -L claude attach -t claude
EOF
chmod 0755 /usr/local/bin/claude-attach

cat >/etc/sudoers.d/claudeop <<'EOF'
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude attach -t claude
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude send-keys *
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude capture-pane *
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude list-sessions
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude load-buffer -
ubuntu ALL=(claudeop) NOPASSWD: /usr/bin/tmux -L claude paste-buffer *
EOF
chmod 0440 /etc/sudoers.d/claudeop
visudo -cf /etc/sudoers.d/claudeop

systemctl daemon-reload
systemctl enable claude-tmux.service

# --- 12.10 try-restart things whose source files were updated ---------------
# Re-runs of setup overwrite /usr/local/bin/twitch-streamd and
# claude-launch.sh; without a restart, the running services keep using
# the old code. try-restart is a no-op if the unit isn't currently active.
systemctl daemon-reload
systemctl try-restart twitch-streamd.service 2>/dev/null || true
systemctl try-restart claude-tmux.service 2>/dev/null || true

# Warn loudly if STREAM_KEY is still empty so the operator notices.
if grep -q '^STREAM_KEY=$' /etc/btone-stream/env; then
  echo ""
  echo "  [setup] NOTE: STREAM_KEY in /etc/btone-stream/env is empty."
  echo "         Twitch streaming will refuse to start until populated."
  echo "         Edit the file (sudo only) then: systemctl restart twitch-streamd"
  echo ""
fi

# --- 13. signal done --------------------------------------------------------
# We can't start xorg-headless yet — it needs the new kernel + nvidia DRM,
# which only come after a reboot. The wrapper does the reboot.
echo "btone-mod-c host bootstrap complete — REBOOT REQUIRED to load nvidia + 6.8 kernel" >/var/lib/btone/.bootstrap-done
