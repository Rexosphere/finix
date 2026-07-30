#!/usr/bin/env bash
# Build the graded submission zip (source only — no .git, build caches, or secrets).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ROOT}/dist"
mkdir -p "$OUT"
STAMP=$(date -u +%Y%m%d)
ZIP="${OUT}/finix-source-${STAMP}.zip"
cd "$ROOT"
zip -r "$ZIP" . \
  -x '.git/*' \
  -x '**/build/*' \
  -x '**/.gradle/*' \
  -x '**/node_modules/*' \
  -x 'dist/*' \
  -x '**/.env' \
  -x '**/*.pem'
echo "Wrote $ZIP"
