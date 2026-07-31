#!/usr/bin/env bash
# Enforce FR-02: /lite must stay under 50 KB transferred (gzip of the static shell).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LITE="$ROOT/apps/web/lite.html"
MAX=51200

if [[ ! -f "$LITE" ]]; then
  echo "missing $LITE" >&2
  exit 1
fi

BYTES=$(wc -c < "$LITE" | tr -d ' ')
echo "apps/web/lite.html = ${BYTES} bytes (limit ${MAX})"
if (( BYTES > MAX )); then
  echo "FAIL: /lite exceeds 50 KB budget" >&2
  exit 1
fi

# Also fail if the file pulls a script tag (zero client JS contract).
if grep -qi '<script' "$LITE"; then
  echo "FAIL: /lite must have zero client JS" >&2
  exit 1
fi

echo "OK: /lite budget and zero-JS contract hold"
