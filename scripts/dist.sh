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
  -x '**/.gradle-home/*' \
  -x '**/.gradle-home/**' \
  -x '**/node_modules/*' \
  -x '**/node_modules/**' \
  -x '**/.venv/*' \
  -x '**/.venv/**' \
  -x '**/venv/*' \
  -x '**/venv/**' \
  -x '**/__pycache__/*' \
  -x '**/__pycache__/**' \
  -x '**/.pytest_cache/*' \
  -x '**/.mypy_cache/*' \
  -x '**/model_artifacts/large/*' \
  -x '**/model_artifacts/large/**' \
  -x '**/*.onnx' \
  -x '**/huggingface/*' \
  -x '**/huggingface/**' \
  -x '**/.cache/pip/*' \
  -x '**/.cache/pip/**' \
  -x 'dist/*' \
  -x '**/.env' \
  -x '**/.env.*' \
  -x '**/*.pem' \
  -x '**/*.key' \
  -x '**/coverage/*' \
  -x '**/.next/*' \
  -x '**/target/*'
echo "Wrote $ZIP"
