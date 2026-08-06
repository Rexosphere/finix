#!/usr/bin/env bash
# Roll production back to an earlier known-good release.
#
#   rollback.sh              previous release (second-to-last in releases.log)
#   rollback.sh <git-sha>    a specific release
#   rollback.sh --list       show the release history
#
# A rollback is an ordinary deploy of an older commit: the images for it are
# still in GHCR under sha-<short>, so this is a pull, not a rebuild. deploy.sh
# does not append rollbacks to releases.log, so the history stays a record of
# what was rolled forward.
set -euo pipefail

STATE_DIR="${FINIX_STATE_DIR:-/opt/finix}"
APP_DIR="${FINIX_APP_DIR:-/opt/finix/app}"
RELEASES="$STATE_DIR/releases.log"

if [ ! -s "$RELEASES" ]; then
  echo "No release history at $RELEASES — nothing to roll back to" >&2
  exit 1
fi

if [ "${1:-}" = "--list" ]; then
  cat "$RELEASES"
  exit 0
fi

target="${1:-}"
if [ -z "$target" ]; then
  target=$(tail -n 2 "$RELEASES" | head -n 1 | awk '{print $2}')
  current=$(tail -n 1 "$RELEASES" | awk '{print $2}')
  if [ "$target" = "$current" ]; then
    echo "Only one release recorded ($current) — pass an explicit sha" >&2
    exit 1
  fi
fi

echo "==> Rolling back to $target"
FINIX_ROLLING_BACK=1 "$APP_DIR/infra/deploy/deploy.sh" "$target"
