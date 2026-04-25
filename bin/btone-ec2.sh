#!/usr/bin/env bash
# btone-ec2 — provision and operate a headless btone-mod-c instance.
#
# Subcommands:
#   ami            Print the latest NixOS x86_64 AMI ID for $AWS_REGION.
#   provision      Deploy/update the CloudFormation stack.
#   ip             Print the instance's Elastic IP from stack outputs.
#   ssh            Open an interactive SSH session as root.
#   push           rsync the flake to /etc/nixos/btone and scp the mod jar.
#   rebuild        ssh + nixos-rebuild switch --flake /etc/nixos/btone#btone-ec2.
#   tunnel         Background ssh -L 25591:127.0.0.1:25591 + scp the bridge
#                  config so bin/btone-cli works locally.
#   logs           journalctl -fu btone-bot on the remote.
#   restart        systemctl restart btone-bot on the remote.
#   destroy        aws cloudformation delete-stack.
#
# Env / config:
#   AWS_REGION     defaults to $AWS_DEFAULT_REGION or us-east-1.
#   STACK_NAME     defaults to btone-ec2.
#   KEY_NAME       AWS key pair name (required for `provision`).
#   KEY_FILE       Path to private key for SSH (default ~/.ssh/$KEY_NAME.pem).
#   INSTANCE_TYPE  defaults to t3.large.
#
# All other steps read state from the CloudFormation outputs — no local
# state file. The skill's runbook walks through the happy path; this
# wrapper is the same commands as one-liners.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
STACK_NAME="${STACK_NAME:-btone-ec2}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.large}"
KEY_NAME="${KEY_NAME:-}"
KEY_FILE="${KEY_FILE:-${HOME}/.ssh/${KEY_NAME}.pem}"
BRIDGE_PORT="${BRIDGE_PORT:-25591}"

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { log "ERROR: $*"; exit 1; }
need() { command -v "$1" >/dev/null || die "$1 not found"; }

need aws
need jq

stack_output() {
  local key="$1"
  aws cloudformation describe-stacks \
    --region "$AWS_REGION" --stack-name "$STACK_NAME" \
    --query "Stacks[0].Outputs[?OutputKey=='${key}'].OutputValue" \
    --output text 2>/dev/null
}

resolve_ip() {
  local ip
  ip=$(stack_output PublicIp)
  [[ -n "$ip" && "$ip" != "None" ]] || die "stack '$STACK_NAME' not found in $AWS_REGION (run: $0 provision)"
  printf '%s' "$ip"
}

cmd_ami() {
  # Owner 427812963091 is the official NixOS AMI publishing account.
  # AMIs are named like `nixos/25.11.9418.c7f47036d3df-x86_64-linux` and
  # GC'd after 90 days, so always resolve the latest dynamically.
  aws ec2 describe-images \
    --region "$AWS_REGION" \
    --owners 427812963091 \
    --filters "Name=name,Values=nixos/*" \
              "Name=architecture,Values=x86_64" \
              "Name=virtualization-type,Values=hvm" \
              "Name=root-device-type,Values=ebs" \
    --query 'sort_by(Images,&CreationDate)[-1].ImageId' \
    --output text
}

cmd_provision() {
  [[ -n "$KEY_NAME" ]] || die "set KEY_NAME=<aws-key-pair-name>"
  local ami my_ip cidr
  ami=$(cmd_ami)
  [[ -n "$ami" && "$ami" != "None" ]] || die "no NixOS AMI found in $AWS_REGION"
  log "AMI: $ami"

  my_ip=$(curl -s ifconfig.me || true)
  cidr="${ALLOWED_SSH_CIDR:-${my_ip:+${my_ip}/32}}"
  [[ -n "$cidr" ]] || die "could not determine your IP; set ALLOWED_SSH_CIDR=x.x.x.x/32"
  log "SSH CIDR: $cidr"

  aws cloudformation deploy \
    --region "$AWS_REGION" \
    --stack-name "$STACK_NAME" \
    --template-file "$REPO_ROOT/infra/btone-ec2.yaml" \
    --parameter-overrides \
        "KeyName=$KEY_NAME" \
        "InstanceType=$INSTANCE_TYPE" \
        "AllowedSshCidr=$cidr" \
        "NixosAmiId=$ami" \
    --no-fail-on-empty-changeset

  log "stack ready. IP: $(resolve_ip)"
}

cmd_ip() { resolve_ip; echo; }

cmd_ssh() {
  local ip
  ip=$(resolve_ip)
  [[ -f "$KEY_FILE" ]] || die "key file not found: $KEY_FILE (set KEY_FILE=...)"
  exec ssh -i "$KEY_FILE" -o StrictHostKeyChecking=accept-new "root@$ip" "$@"
}

cmd_push() {
  local ip jar
  ip=$(resolve_ip)
  jar=$(ls -t "$REPO_ROOT"/mod-c/build/libs/btone-mod-c-*.jar 2>/dev/null | head -n1)
  [[ -n "$jar" ]] || die "no mod jar built — run: cd mod-c && nix develop .. --command ./gradlew build"
  log "syncing flake to /etc/nixos/btone"
  ssh -i "$KEY_FILE" "root@$ip" "mkdir -p /etc/nixos/btone /var/lib/btone/mods"
  rsync -a -e "ssh -i $KEY_FILE" \
    "$REPO_ROOT/flake.nix" "$REPO_ROOT/flake.lock" \
    "$REPO_ROOT/infra/" \
    "root@$ip:/etc/nixos/btone/"
  # The flake expects ./infra/nixos.nix. rsync of infra/ flattens into
  # /etc/nixos/btone/, so move the file into the path the flake imports.
  ssh -i "$KEY_FILE" "root@$ip" "
    set -e
    mkdir -p /etc/nixos/btone/infra
    [ -f /etc/nixos/btone/nixos.nix ] && mv /etc/nixos/btone/nixos.nix /etc/nixos/btone/infra/nixos.nix
    [ -f /etc/nixos/btone/btone-ec2.yaml ] && mv /etc/nixos/btone/btone-ec2.yaml /etc/nixos/btone/infra/btone-ec2.yaml
    true
  "
  log "uploading mod jar: $(basename "$jar")"
  scp -i "$KEY_FILE" "$jar" "root@$ip:/var/lib/btone/mods/btone-mod-c-0.1.0.jar"
  # btone user only exists after the first nixos-rebuild — best-effort chown.
  # The rebuild step does it again unconditionally once the user is created.
  ssh -i "$KEY_FILE" "root@$ip" "chown -R btone:btone /var/lib/btone 2>/dev/null || true"
}

cmd_rebuild() {
  local ip
  ip=$(resolve_ip)
  # NIX_CONFIG enables flakes for the duration of this command, which
  # matters on the very first rebuild — stock NixOS AMIs ship with
  # flakes off. The activated config (via nix.settings in nixos.nix)
  # makes this redundant on subsequent rebuilds, but harmless.
  ssh -i "$KEY_FILE" "root@$ip" \
    "NIX_CONFIG='experimental-features = nix-command flakes' \
     nixos-rebuild switch --flake /etc/nixos/btone#btone-ec2 && \
     chown -R btone:btone /var/lib/btone"
}

cmd_tunnel() {
  local ip cfg_dir cfg_path
  ip=$(resolve_ip)
  cfg_dir="$HOME/btone-mc-work/config"
  cfg_path="$cfg_dir/btone-bridge.json"
  mkdir -p "$cfg_dir"

  log "fetching bridge config from instance"
  scp -i "$KEY_FILE" \
    "root@$ip:/var/lib/btone/.minecraft/config/btone-bridge.json" \
    "$cfg_path" \
    || die "bridge config not found on instance — is btone-bot running? (try: $0 logs)"

  # Patch the host to localhost (config records 127.0.0.1 already, but the
  # port may have been auto-bumped if 25591 was busy in MC's JVM).
  local port
  port=$(jq -r .port "$cfg_path")
  log "bridge port on instance: $port; opening local tunnel localhost:$BRIDGE_PORT -> instance:$port"

  pkill -f "ssh.*-L $BRIDGE_PORT:127.0.0.1" 2>/dev/null || true
  ssh -i "$KEY_FILE" -fN -L "$BRIDGE_PORT:127.0.0.1:$port" "root@$ip"

  # Rewrite the local config to use $BRIDGE_PORT (what bin/btone-cli reads).
  jq --argjson p "$BRIDGE_PORT" '.port = $p' "$cfg_path" > "$cfg_path.tmp" && mv "$cfg_path.tmp" "$cfg_path"
  log "tunnel up. test with: bin/btone-cli player.state"
}

cmd_logs() { cmd_ssh -- "journalctl -fu btone-bot"; }
cmd_restart() { cmd_ssh -- "systemctl restart btone-bot"; }

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
  ami)        cmd_ami "$@" ;;
  provision)  cmd_provision "$@" ;;
  ip)         cmd_ip "$@" ;;
  ssh)        cmd_ssh "$@" ;;
  push)       cmd_push "$@" ;;
  rebuild)    cmd_rebuild "$@" ;;
  tunnel)     cmd_tunnel "$@" ;;
  logs)       cmd_logs "$@" ;;
  restart)    cmd_restart "$@" ;;
  destroy)    cmd_destroy "$@" ;;
  -h|--help)  usage ;;
  *)          die "unknown subcommand: $cmd (try: $0 --help)" ;;
esac
