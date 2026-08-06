#!/usr/bin/env bash
# make demo-pull — pull published GHCR images, start core stack, wait, seed, print URLs.
# Needs Docker only (no JDK / Gradle).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPOSE=(docker compose -f infra/compose/docker-compose.yml --profile core)
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-finix}"
export FINIX_IMAGE_PREFIX="${FINIX_IMAGE_PREFIX:-ghcr.io/rexosphere/finix}"
export FINIX_IMAGE_TAG="${FINIX_IMAGE_TAG:-latest}"

./scripts/gen-secrets.sh

echo "==> Pulling published images (${FINIX_IMAGE_PREFIX}:*:${FINIX_IMAGE_TAG})"
"${COMPOSE[@]}" pull

echo "==> Starting compose (core, no local build)"
"${COMPOSE[@]}" up -d --no-build

./scripts/wait-seed-banner.sh
