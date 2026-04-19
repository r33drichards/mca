#!/usr/bin/env bash
# Bootstrap a PortableMC work directory for the btone Bot.
# Installs PortableMC, creates ~/btone-mc-work/, downloads the required mods,
# and copies in the freshly built btone-mod-c JAR.
#
# Requires: nix (for the flake-provided JDK), python3+pip, jq, curl.
#
# Usage: bin/setup-portablemc.sh [<server-host:port>]
#        Default server: centerbeam.proxy.rlwy.net:40387
set -euo pipefail

MC_VERSION="1.21.8"
FABRIC_LOADER="0.19.2"
SERVER="${1:-centerbeam.proxy.rlwy.net:40387}"
WORK="${WORK:-$HOME/btone-mc-work}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

command -v jq >/dev/null   || die "jq not found"
command -v curl >/dev/null || die "curl not found"
command -v python3 >/dev/null || die "python3 not found"
command -v nix >/dev/null   || die "nix not found"

# 1. PortableMC ----------------------------------------------------------------
if ! command -v portablemc >/dev/null; then
    log "installing portablemc via pip --user"
    python3 -m pip install --user --break-system-packages portablemc
fi
PMC_BIN=$(command -v portablemc)
[[ -z "$PMC_BIN" ]] && PMC_BIN="$HOME/Library/Python/$(python3 -c 'import sys;print(f"{sys.version_info.major}.{sys.version_info.minor}")')/bin/portablemc"

# 2. Mods ----------------------------------------------------------------------
mkdir -p "$WORK/mods"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

fetch_modrinth() {
    local slug="$1" label="$2"
    local url
    url=$(curl -sfL "https://api.modrinth.com/v2/project/${slug}/version?game_versions=%5B%22${MC_VERSION}%22%5D&loaders=%5B%22fabric%22%5D" \
        | jq -r '.[0].files[] | select(.primary == true) | .url' | head -n1)
    [[ -n "$url" && "$url" != "null" ]] || die "$label: no $MC_VERSION fabric release on modrinth"
    local fn="$STAGE/$(basename "$url")"
    curl -sfL -o "$fn" "$url"
    log "$label downloaded"
    cp "$fn" "$WORK/mods/"
}

log "fetching fabric-api"
fetch_modrinth fabric-api "fabric-api"

log "fetching fabric-language-kotlin"
fetch_modrinth fabric-language-kotlin "fabric-language-kotlin"

log "fetching meteor-client (via meteorclient.com — Modrinth doesn't host)"
curl -sfL -o "$WORK/mods/meteor-client-${MC_VERSION}.jar" "https://meteorclient.com/api/download?version=${MC_VERSION}"
[[ $(stat -f%z "$WORK/mods/meteor-client-${MC_VERSION}.jar" 2>/dev/null || stat -c%s "$WORK/mods/meteor-client-${MC_VERSION}.jar") -gt 1000000 ]] \
    || die "meteor download too small — endpoint may be down"

log "fetching baritone-api-fabric (NOT standalone — that one over-obfuscates the API)"
curl -sfL -o "$WORK/mods/baritone-api-fabric-1.15.0.jar" \
    "https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar"

log "fetching sodium (render performance — without this MC's render thread saturates during bot mining and the server times the client out)"
fetch_modrinth sodium "sodium"

# 3. Build + copy our mod -------------------------------------------------------
BTONE_JAR="$REPO_ROOT/mod-c/build/libs/btone-mod-c-0.1.0.jar"
if [[ ! -f "$BTONE_JAR" ]]; then
    log "building btone-mod-c"
    (cd "$REPO_ROOT/mod-c" && nix develop "$REPO_ROOT" --command ./gradlew build)
fi
cp "$BTONE_JAR" "$WORK/mods/"
log "mod jars in $WORK/mods/:"
ls -1 "$WORK/mods/" >&2

# 4. Launch instructions --------------------------------------------------------
JVM_DEFAULT="$(nix develop "$REPO_ROOT" --command bash -c 'echo $JAVA_HOME/bin/java')"
UUID="$(uuidgen | tr A-Z a-z)"

cat <<NEXT

Setup complete.

To launch the bot:

  ${PMC_BIN} --work-dir "${WORK}" start fabric:${MC_VERSION}:${FABRIC_LOADER} \\
    --jvm "${JVM_DEFAULT}" --auth-anonymize \\
    -u Bot -i ${UUID} \\
    -s ${SERVER%:*} -p ${SERVER##*:}

The mod will write its bridge config to:
  ${WORK}/config/btone-bridge.json

Read port + token from that file to talk to the bot via HTTP at 127.0.0.1.
NEXT
