#!/usr/bin/env bash
# btone-ec2 — provision and operate a headless btone-mod-c instance
# (Ubuntu 24.04, software-rendered MC under Xvfb + snd-dummy).
#
# Subcommands:
#   provision   Deploy/update the CloudFormation stack.
#   ip          Print the instance's Elastic IP from stack outputs.
#   ssh         Open an interactive SSH session as `ubuntu`.
#   setup       scp infra/setup-ubuntu.sh to the box, run as root.
#               Run once after provision (idempotent).
#   push        scp the locally-built mod-c jar; restart bot.
#   tunnel      Background ssh -L 25591:127.0.0.1:25591 + scp the bridge
#               config so bin/btone-cli works locally.
#   logs        journalctl -fu btone-bot on the remote.
#   restart     systemctl restart btone-bot on the remote.
#   claude      ssh + tmux attach the sandboxed Claude Code session.
#   claude-send send-keys text into the Claude session (no attach).
#   claude-peek capture-pane and print the last N lines (default 30).
#   destroy     aws cloudformation delete-stack.
#
# Env / config:
#   AWS_REGION     defaults to us-west-2.
#   STACK_NAME     defaults to btone-ec2.
#   KEY_NAME       AWS key pair name (required for `provision`).
#   KEY_FILE       Path to private key for SSH (default ~/.ssh/$KEY_NAME.pem).
#   INSTANCE_TYPE  defaults to c7i.xlarge.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-west-2}}"
STACK_NAME="${STACK_NAME:-btone-ec2}"
INSTANCE_TYPE="${INSTANCE_TYPE:-g4dn.xlarge}"
KEY_NAME="${KEY_NAME:-}"
KEY_FILE="${KEY_FILE:-${HOME}/.ssh/${KEY_NAME}.pem}"
# Default to 25592 locally because most operators have the laptop-side
# bot already listening on 25591. Override via BRIDGE_PORT env.
BRIDGE_PORT="${BRIDGE_PORT:-25592}"
SSH_USER="ubuntu"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { log "ERROR: $*"; exit 1; }
need() { command -v "$1" >/dev/null || die "$1 not found"; }

need aws
need jq

stack_output() {
  aws cloudformation describe-stacks \
    --region "$AWS_REGION" --stack-name "$STACK_NAME" \
    --query "Stacks[0].Outputs[?OutputKey=='${1}'].OutputValue" \
    --output text 2>/dev/null
}

resolve_ip() {
  local ip
  ip=$(stack_output PublicIp)
  [[ -n "$ip" && "$ip" != "None" ]] || die "stack '$STACK_NAME' not found in $AWS_REGION (run: $0 provision)"
  printf '%s' "$ip"
}

ssh_args=(-i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR)

cmd_provision() {
  [[ -n "$KEY_NAME" ]] || die "set KEY_NAME=<aws-key-pair-name>"
  local cidr
  cidr="${ALLOWED_SSH_CIDR:-$(curl -s4 https://api.ipify.org)/32}"
  [[ "$cidr" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/[0-9]{1,2}$ ]] \
    || die "could not determine v4 IP; set ALLOWED_SSH_CIDR=x.x.x.x/32"
  log "SSH CIDR: $cidr"

  aws cloudformation deploy \
    --region "$AWS_REGION" \
    --stack-name "$STACK_NAME" \
    --template-file "$REPO_ROOT/infra/btone-ec2.yaml" \
    --parameter-overrides \
        "KeyName=$KEY_NAME" \
        "InstanceType=$INSTANCE_TYPE" \
        "AllowedSshCidr=$cidr" \
    --no-fail-on-empty-changeset

  log "stack ready. IP: $(resolve_ip)"
}

cmd_ip() { resolve_ip; echo; }

cmd_ssh() {
  local ip
  ip=$(resolve_ip)
  [[ -f "$KEY_FILE" ]] || die "key file not found: $KEY_FILE (set KEY_FILE=...)"
  exec ssh "${ssh_args[@]}" "$SSH_USER@$ip" "$@"
}

# Wait for SSH to come up after a fresh launch.
wait_ssh() {
  local ip="$1"
  local i
  for i in $(seq 1 60); do
    ssh "${ssh_args[@]}" -o ConnectTimeout=5 -o BatchMode=yes "$SSH_USER@$ip" 'true' 2>/dev/null && return 0
    sleep 5
  done
  die "ssh never came up after 5min"
}

cmd_setup() {
  local ip
  ip=$(resolve_ip)
  log "waiting for ssh on $ip"
  wait_ssh "$ip"

  log "uploading setup script"
  scp "${ssh_args[@]}" "$REPO_ROOT/infra/setup-ubuntu.sh" "$SSH_USER@$ip:/tmp/setup-ubuntu.sh"

  log "running setup as root (this takes ~5-10min — apt + nvidia driver + mod build)"
  ssh "${ssh_args[@]}" "$SSH_USER@$ip" "sudo bash /tmp/setup-ubuntu.sh" 2>&1 | tail -30

  log "rebooting to load 6.8 kernel + nvidia DRM module"
  ssh "${ssh_args[@]}" "$SSH_USER@$ip" "sudo reboot" 2>/dev/null || true
  sleep 30
  wait_ssh "$ip"

  log "starting btone-bot now that kernel/DRM are ready"
  ssh "${ssh_args[@]}" "$SSH_USER@$ip" "sudo systemctl start btone-bot" 2>&1 | tail -5
  log "setup complete. Watch logs: $0 logs   Tunnel: $0 tunnel"
}

cmd_push() {
  # The mod is BUILT on the instance — push = `git pull && ./gradlew build &&
  # systemctl restart btone-bot`. Refuses if local mod-c/ has uncommitted
  # changes (git pull can't see them).
  local ip
  ip=$(resolve_ip)

  if ! git -C "$REPO_ROOT" diff --quiet HEAD -- mod-c/ 2>/dev/null; then
    die "uncommitted changes under mod-c/ — commit + push first (git pull on the instance can't see them)"
  fi
  local sha
  sha=$(git -C "$REPO_ROOT" rev-parse HEAD)
  log "deploying mod-c @ $sha"

  ssh "${ssh_args[@]}" "$SSH_USER@$ip" "
    set -e
    sudo -u btone -H bash <<EOSH
      set -e
      cd /var/lib/btone/source
      git fetch origin
      git reset --hard $sha
      cd mod-c
      JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon --console=plain build
EOSH
    sudo install -o btone -g btone -m 0644 \
      /var/lib/btone/source/mod-c/build/libs/btone-mod-c-0.1.0.jar \
      /var/lib/btone/mods/btone-mod-c-0.1.0.jar
    sudo systemctl restart btone-bot
  "
  log "bot restarted; tail with: $0 logs"
}

cmd_tunnel() {
  local ip cfg_dir cfg_path port
  ip=$(resolve_ip)
  cfg_dir="$HOME/btone-mc-work/config"
  cfg_path="$cfg_dir/btone-bridge.json"
  mkdir -p "$cfg_dir"

  log "fetching bridge config from instance"
  ssh "${ssh_args[@]}" "$SSH_USER@$ip" "sudo cat /var/lib/btone/config/btone-bridge.json" \
    > "$cfg_path" 2>/dev/null \
    || die "bridge config not found — is btone-bot running? (try: $0 logs)"

  port=$(jq -r .port "$cfg_path")
  log "bridge port on instance: $port; opening tunnel localhost:$BRIDGE_PORT -> instance:$port"

  pkill -f "ssh.*-L $BRIDGE_PORT:127.0.0.1" 2>/dev/null || true
  ssh "${ssh_args[@]}" -fN -L "$BRIDGE_PORT:127.0.0.1:$port" "$SSH_USER@$ip"

  jq --argjson p "$BRIDGE_PORT" '.port = $p' "$cfg_path" > "$cfg_path.tmp" && mv "$cfg_path.tmp" "$cfg_path"
  log "tunnel up. test with: bin/btone-cli player.state"
}

cmd_logs()    { cmd_ssh -- "sudo journalctl -fu btone-bot"; }
cmd_restart() { cmd_ssh -- "sudo systemctl restart btone-bot"; }

cmd_claude() {
  local ip
  ip=$(resolve_ip)
  [[ -f "$KEY_FILE" ]] || die "key file not found: $KEY_FILE"
  exec ssh "${ssh_args[@]}" -t "$SSH_USER@$ip" claude-attach
}

cmd_claude_send() {
  # Pipe message through stdin → tmux load-buffer → paste-buffer → Enter.
  # Avoids shell-quoting layers (local → ssh → sudo → tmux argv) which
  # would otherwise let $(...) inside a message get evaluated remotely.
  local ip msg
  ip=$(resolve_ip)
  msg="$*"
  [[ -n "$msg" ]] || die "usage: $0 claude-send <text>"
  printf '%s' "$msg" | ssh "${ssh_args[@]}" "$SSH_USER@$ip" "
    sudo -u claudeop /usr/bin/tmux -L claude load-buffer - &&
    sudo -u claudeop /usr/bin/tmux -L claude paste-buffer -t claude:0 -d &&
    sudo -u claudeop /usr/bin/tmux -L claude send-keys -t claude:0 Enter
  "
}

cmd_claude_peek() {
  local ip n
  ip=$(resolve_ip)
  n="${1:-30}"
  [[ "$n" =~ ^[0-9]+$ ]] || die "claude-peek expects a numeric line count, got: $n"
  ssh "${ssh_args[@]}" "$SSH_USER@$ip" \
    "sudo -u claudeop /usr/bin/tmux -L claude capture-pane -t claude:0 -p" \
    | tail -n "$n"
}

cmd_destroy() {
  log "deleting stack $STACK_NAME in $AWS_REGION"
  aws cloudformation delete-stack --region "$AWS_REGION" --stack-name "$STACK_NAME"
  aws cloudformation wait stack-delete-complete --region "$AWS_REGION" --stack-name "$STACK_NAME"
  log "stack deleted"
}

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//;/^set -euo/d'
  exit 2
}

cmd="${1:-}"
[[ -n "$cmd" ]] || usage
shift || true

case "$cmd" in
  provision)  cmd_provision "$@" ;;
  ip)         cmd_ip "$@" ;;
  ssh)        cmd_ssh "$@" ;;
  setup)      cmd_setup "$@" ;;
  push)       cmd_push "$@" ;;
  tunnel)     cmd_tunnel "$@" ;;
  logs)       cmd_logs "$@" ;;
  restart)    cmd_restart "$@" ;;
  claude)     cmd_claude "$@" ;;
  claude-send) cmd_claude_send "$@" ;;
  claude-peek) cmd_claude_peek "$@" ;;
  destroy)    cmd_destroy "$@" ;;
  -h|--help)  usage ;;
  *)          die "unknown subcommand: $cmd (try: $0 --help)" ;;
esac
