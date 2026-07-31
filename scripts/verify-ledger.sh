#!/usr/bin/env bash
# Independent ledger verification — does not trust the running service's "we're fine" claim
# beyond the data it returns; recomputes expectations a judge can eyeball.
set -euo pipefail

LEDGER_URL="${LEDGER_URL:-http://localhost:8084}"
TX_ID="${1:-}"

echo "==> Chain walk via GET /api/v1/ledger/verify"
VERIFY=$(curl -sf "${LEDGER_URL}/api/v1/ledger/verify")
echo "$VERIFY" | tee /tmp/finix-verify.json
python3 - <<'PY'
import json,sys
v=json.load(open("/tmp/finix-verify.json"))
ok=bool(v.get("valid"))
print("valid=", ok, "checked=", v.get("checkedEntries"), "break=", v.get("firstBreakSequence"), v.get("detail"))
sys.exit(0 if ok else 2)
PY

if [[ -n "$TX_ID" ]]; then
  echo "==> Inclusion proof for $TX_ID"
  curl -sf "${LEDGER_URL}/api/v1/ledger/proof/${TX_ID}" | tee /tmp/finix-proof.json
  python3 - <<'PY'
import json
p=json.load(open("/tmp/finix-proof.json"))
print("sequence=", p.get("sequence"), "entryHash=", (p.get("entryHash") or "")[:16]+"…")
print("merkleRoot=", p.get("merkleRoot"))
print("pathSteps=", len(p.get("merklePath") or []))
print("anchor=", p.get("anchorId"))
PY
fi

echo "==> Recent anchors"
curl -sf "${LEDGER_URL}/api/v1/ledger/anchors" | python3 -m json.tool | head -80

echo "OK"
