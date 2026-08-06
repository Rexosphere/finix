#!/usr/bin/env bash
# FINIX production deploy — runs on the server, invoked by .github/workflows/deploy.yml.
#
#   deploy.sh [<git-sha>]      deploy that commit (default: current origin/HEAD)
#
# What changed and why (ADR-0007): this used to git-pull master, run nine Gradle
# bootJar tasks and `docker compose up --build` on the server, which meant
# production ran an artifact no CI job had ever seen. It now pulls the images
# that CI built and tested for exactly this commit, tagged sha-<short>.
#
# Guarantees:
#   - the compose files, app assets and image tag all come from one commit
#   - a deploy that does not reach a healthy state is rolled back to the last
#     good release and exits non-zero, so the workflow goes red
set -euo pipefail

APP_DIR="${FINIX_APP_DIR:-/opt/finix/app}"
STATE_DIR="${FINIX_STATE_DIR:-/opt/finix}"
ENV_FILE="${FINIX_ENV_FILE:-$STATE_DIR/finix.env}"
RELEASES="$STATE_DIR/releases.log"
REPO_URL="${FINIX_REPO_URL:-https://github.com/Rexosphere/finix.git}"
HEALTH_TIMEOUT="${FINIX_HEALTH_TIMEOUT:-600}"

# Set by the caller to skip the rollback attempt — the rollback deploy itself
# must not recurse if it also fails.
ROLLING_BACK="${FINIX_ROLLING_BACK:-0}"

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-finix}"

log() { printf '==> %s\n' "$*"; }

if [ ! -d "$APP_DIR/.git" ]; then
  log "Cloning $REPO_URL"
  git clone "$REPO_URL" "$APP_DIR"
fi

git -C "$APP_DIR" fetch --prune --tags origin

TARGET_SHA="${1:-$(git -C "$APP_DIR" rev-parse origin/HEAD)}"
TARGET_SHA="$(git -C "$APP_DIR" rev-parse "$TARGET_SHA")"
SHORT_SHA="$(git -C "$APP_DIR" rev-parse --short=7 "$TARGET_SHA")"

log "Deploying $SHORT_SHA ($TARGET_SHA)"

# Detached checkout of the exact commit: compose files, nginx configs and static
# assets are then the same revision as the images below. No sed, no local edits —
# the working tree is only ever a build artifact.
git -C "$APP_DIR" checkout --force --detach "$TARGET_SHA"

cd "$APP_DIR"

COMPOSE=(docker compose
  -f "$APP_DIR/infra/compose/docker-compose.yml"
  -f "$APP_DIR/infra/compose/docker-compose.prod.yml"
  -f "$APP_DIR/infra/compose/docker-compose.vault.yml"
  --profile full)
if [ -f "$ENV_FILE" ]; then
  COMPOSE=(docker compose --env-file "$ENV_FILE" "${COMPOSE[@]:2}")
fi

# Credentials are files on this host (ADR-0006), written by the Ansible finix
# role. Never generated here: regenerating would invalidate the Postgres roles.
if [ ! -s "$APP_DIR/infra/compose/secrets/postgres_superuser_password" ]; then
  echo "Missing $APP_DIR/infra/compose/secrets/ — run the Ansible finix role first" >&2
  exit 1
fi

# The image set for this commit. CI publishes sha-<short> from the same commit,
# so this is an immutable coordinate, unlike `latest`.
export FINIX_IMAGE_TAG="sha-${SHORT_SHA}"
export FINIX_IMAGE_PREFIX="${FINIX_IMAGE_PREFIX:-ghcr.io/rexosphere/finix}"

log "Pulling images ${FINIX_IMAGE_PREFIX}/*:${FINIX_IMAGE_TAG}"
"${COMPOSE[@]}" pull --quiet

log "Starting stack"
"${COMPOSE[@]}" up -d --no-build --remove-orphans

# web/admin bind-mount their nginx template. A checkout replaces that file rather
# than editing it in place, so the running container keeps the old inode and a
# config change would silently never apply. Compose will not recreate them by
# itself either, because the mount spec is unchanged.
log "Recreating static app containers"
"${COMPOSE[@]}" up -d --force-recreate --no-deps web admin

wait_healthy() {
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while :; do
    local pending=0 failed=0 cid status name exit_code
    for cid in $("${COMPOSE[@]}" ps -q); do
      name=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$cid")
      # Containers without a HEALTHCHECK fall back to their run state.
      status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid")
      case "$status" in
        healthy | running) ;;
        starting) pending=$((pending + 1)) ;;
        exited)
          # One-shot containers (vault-bootstrap) legitimately exit — but only 0.
          exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$cid")
          if [ "$exit_code" != "0" ]; then
            echo "    $name exited with $exit_code"
            failed=$((failed + 1))
          fi
          ;;
        *)
          echo "    $name: $status"
          failed=$((failed + 1))
          ;;
      esac
    done

    if [ "$pending" -eq 0 ] && [ "$failed" -eq 0 ]; then
      return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "    timed out after ${HEALTH_TIMEOUT}s: $pending starting, $failed unhealthy" >&2
      return 1
    fi
    sleep 5
  done
}

log "Waiting for health (timeout ${HEALTH_TIMEOUT}s)"
if ! wait_healthy; then
  "${COMPOSE[@]}" ps
  "${COMPOSE[@]}" logs --tail=60 || true

  previous=""
  if [ -s "$RELEASES" ]; then
    previous=$(tail -n 1 "$RELEASES" | awk '{print $2}')
  fi

  if [ "$ROLLING_BACK" = "1" ] || [ -z "$previous" ] || [ "$previous" = "$TARGET_SHA" ]; then
    echo "::error::deploy of $SHORT_SHA failed and there is no earlier release to roll back to" >&2
    exit 1
  fi

  log "UNHEALTHY — rolling back to $previous"
  FINIX_ROLLING_BACK=1 "$APP_DIR/infra/deploy/deploy.sh" "$previous"
  echo "::error::deploy of $SHORT_SHA failed; rolled back to $previous" >&2
  exit 1
fi

./scripts/seed.sh || log "seed reported an error (services are healthy; continuing)"

# Append only after health passed: releases.log is the list of known-good
# revisions, which is exactly what rollback needs.
if [ "$ROLLING_BACK" != "1" ]; then
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TARGET_SHA" >> "$RELEASES"
fi

log "Deployed $SHORT_SHA successfully"
