#!/usr/bin/env bash
# Explicitly upload reviewed server files into a new staging directory; no live restart.
set -euo pipefail
: "${FAMILYLEDGER_NAS_HOST:?Set the intended NAS hostname}"
: "${FAMILYLEDGER_NAS_USER:?Set the intended SSH user}"
: "${FAMILYLEDGER_SSH_KEY:?Set an existing private key path outside the repository}"
SSH_PORT="${FAMILYLEDGER_SSH_PORT:-22}"
# A simple safe remote path is required because SSH executes a remote shell.
BASE="${FAMILYLEDGER_REMOTE_BASE:-/volume1/docker/familyledger}"
[[ "$FAMILYLEDGER_NAS_HOST" =~ ^[a-zA-Z0-9.-]+$ && "$FAMILYLEDGER_NAS_USER" =~ ^[a-zA-Z0-9_-]+$ && "$SSH_PORT" =~ ^[0-9]+$ && "$BASE" =~ ^/[a-zA-Z0-9/_-]+$ ]] || { echo "Unsafe deployment argument" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
STAGE="$BASE/staging-$(date +%Y%m%d-%H%M%S)"
SSH_OPTS=(-i "$FAMILYLEDGER_SSH_KEY" -p "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=yes)
ssh "${SSH_OPTS[@]}" "$FAMILYLEDGER_NAS_USER@$FAMILYLEDGER_NAS_HOST" "mkdir -m 700 '$STAGE'"
tar -C "$HERE" -cf - familyledger_sync_server.py healthcheck.py smokecheck.py Dockerfile upgrade_on_nas.py |
  ssh "${SSH_OPTS[@]}" "$FAMILYLEDGER_NAS_USER@$FAMILYLEDGER_NAS_HOST" "tar -xf - -C '$STAGE'"
printf 'Uploaded to %s. Review backups and upgrade instructions before changing the running service.\n' "$STAGE"
