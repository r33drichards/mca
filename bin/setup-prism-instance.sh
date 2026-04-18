#!/usr/bin/env bash
# Bootstrap a Prism Launcher instance for btone-b-dev: MC 1.21.8 + Fabric,
# with Fabric API, Fabric Language Kotlin, Baritone standalone, Meteor Client,
# and the btone-mod-b JAR preinstalled.
#
# Requires: brew (for Prism install), jq, curl, nix (for the flake dev shell).
# Assumes:  Prism already has at least one Microsoft account configured, OR the
#           user will add one after running this script.
#
# Usage: bin/setup-prism-instance.sh
set -euo pipefail

MC_VERSION="1.21.8"
FABRIC_LOADER="0.19.2"
FABRIC_INTERMEDIARY="1.21.8"
INSTANCE_NAME="btone-b-dev"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRISM_ROOT="$HOME/Library/Application Support/PrismLauncher"
INSTANCE_DIR="$PRISM_ROOT/instances/$INSTANCE_NAME"
GAME_DIR="$INSTANCE_DIR/.minecraft"
MODS_DIR="$GAME_DIR/mods"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# ---- 1. Prereq checks ----
command -v brew >/dev/null || die "brew not installed. Install Homebrew first."
command -v jq   >/dev/null || die "jq not found."
command -v curl >/dev/null || die "curl not found."

# ---- 2. Install Prism Launcher if absent ----
if [[ ! -d "/Applications/Prism Launcher.app" ]]; then
    log "Prism Launcher not installed; running 'brew install --cask prismlauncher'"
    log "(You may be prompted for your password.)"
    brew install --cask prismlauncher
else
    log "Prism Launcher present at /Applications/Prism Launcher.app"
fi

# ---- 3. Ensure Prism support dir exists ----
if [[ ! -d "$PRISM_ROOT" ]]; then
    log "PrismLauncher config dir missing. Launching Prism once to initialize."
    log "Close Prism after the main window appears, then re-run this script."
    open -a "Prism Launcher"
    exit 2
fi

mkdir -p "$INSTANCE_DIR" "$GAME_DIR" "$MODS_DIR"

# ---- 4. Write mmc-pack.json ----
cat > "$INSTANCE_DIR/mmc-pack.json" <<JSON
{
  "components": [
    { "important": true, "uid": "net.minecraft", "version": "$MC_VERSION" },
    { "dependencyOnly": true, "uid": "net.fabricmc.intermediary", "version": "$FABRIC_INTERMEDIARY" },
    { "uid": "net.fabricmc.fabric-loader", "version": "$FABRIC_LOADER" }
  ],
  "formatVersion": 1
}
JSON

cat > "$INSTANCE_DIR/instance.cfg" <<CFG
InstanceType=OneSix
name=$INSTANCE_NAME
notes=btone-mod-b dev instance, auto-generated
iconKey=default
CFG

log "Instance directory: $INSTANCE_DIR"

# ---- 5. Download mods via Modrinth API ----
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

fetch_modrinth() {
    local slug="$1" label="$2"
    local url
    url=$(curl -sfL "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${MC_VERSION}%22%5D&loaders=%5B%22fabric%22%5D" \
        | jq -r '.[0].files[] | select(.primary == true) | .url' | head -n1)
    [[ -n "$url" && "$url" != "null" ]] || die "$label: no 1.21.8 Fabric release on Modrinth"
    local fn="$STAGE/$(basename "$url")"
    curl -sfL -o "$fn" "$url"
    log "Downloaded $label: $(basename "$fn")"
    printf '%s\n' "$fn"
}

log "Fetching Fabric API..."
FAB_API=$(fetch_modrinth fabric-api "Fabric API")
log "Fetching Fabric Language Kotlin..."
FAB_KT=$(fetch_modrinth fabric-language-kotlin "Fabric Language Kotlin")
log "Fetching Meteor Client for $MC_VERSION..."
# Meteor distributes via meteorclient.com, not Modrinth. Use their ?version= API.
METEOR="$STAGE/meteor-client-${MC_VERSION}.jar"
curl -sfL -o "$METEOR" "https://meteorclient.com/api/download?version=${MC_VERSION}" \
    || die "Meteor direct download failed for $MC_VERSION"
METEOR_SIZE=$(stat -f%z "$METEOR" 2>/dev/null || stat -c%s "$METEOR")
(( METEOR_SIZE > 1000000 )) || die "Meteor download too small ($METEOR_SIZE bytes); endpoint may have failed"
log "Downloaded Meteor: $(basename "$METEOR") ($METEOR_SIZE bytes)"

# Baritone: GitHub release (not on Modrinth)
BARI="$STAGE/baritone-standalone-fabric-1.15.0.jar"
log "Fetching Baritone standalone 1.15.0..."
curl -sfL -o "$BARI" \
  "https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-standalone-fabric-1.15.0.jar" \
  || die "Baritone download failed"

# ---- 6. Copy mods into instance ----
cp "$FAB_API" "$FAB_KT" "$METEOR" "$BARI" "$MODS_DIR/"

BTONE_JAR="$REPO_ROOT/mod-b/build/libs/btone-mod-b-0.1.0.jar"
if [[ ! -f "$BTONE_JAR" ]]; then
    log "btone-mod-b JAR missing; building..."
    (cd "$REPO_ROOT/mod-b" && nix develop .. --command ./gradlew build)
fi
cp "$BTONE_JAR" "$MODS_DIR/"

log "Mods installed:"
ls -1 "$MODS_DIR" >&2

# ---- 7. Done ----
cat <<NEXT

Setup complete. Next steps:

1. Open Prism Launcher.
2. If no Microsoft account yet: Settings -> Accounts -> Add Microsoft -> follow OAuth.
3. The '$INSTANCE_NAME' instance should appear; double-click to launch.
4. Connect to your test server: centerbeam.proxy.rlwy.net:40387
5. In another terminal, extract the token and test the bridge:

     TOKEN=\$(jq -r .token "$GAME_DIR/config/btone-bridge.json")
     curl -s -H "Authorization: Bearer \$TOKEN" http://127.0.0.1:25590/health

   Then follow $REPO_ROOT/mod-b/SMOKE.md.

NEXT
