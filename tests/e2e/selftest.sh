#!/usr/bin/env bash
#
# Self-test for tests/e2e/finix-smoke.sh.
#
# Runs the harness end to end against lib/mock_finix.py — a stand-in FINIX edge — so the
# harness itself can be validated on a machine with no Docker and without starting, or
# going anywhere near, a real deployment. It checks three things the harness must get
# right and that a green run against a healthy stack would never prove:
#
#   1. --read-only really is read-only (the mock's state is byte-identical afterwards);
#   2. the full run passes every check and leaves the demo balances where it found them;
#   3. injected faults actually fail — a harness that cannot go red is decoration.
#
# Usage: bash tests/e2e/selftest.sh
#
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
HARNESS="$SCRIPT_DIR/finix-smoke.sh"
MOCK="$SCRIPT_DIR/lib/mock_finix.py"
HELPER="$SCRIPT_DIR/lib/finix_smoke_helper.py"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/finix-selftest.XXXXXX")"
MOCK_PID=""
cleanup() {
  [[ -n "$MOCK_PID" ]] && kill "$MOCK_PID" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

if [[ -t 1 ]]; then
  G=$'\033[32m'; R=$'\033[31m'; B=$'\033[1m'; O=$'\033[0m'
else
  G=""; R=""; B=""; O=""
fi

TESTS=0; FAILURES=0
ok()   { TESTS=$((TESTS+1)); printf '  %sPASS%s %s\n' "$G" "$O" "$1"; }
bad()  { TESTS=$((TESTS+1)); FAILURES=$((FAILURES+1)); printf '  %sFAIL%s %s\n' "$R" "$O" "$1"; }
head_() { printf '\n%s%s%s\n' "$B" "$1" "$O"; }

assert_exit() { # expected actual label
  if [[ "$1" == "$2" ]]; then ok "$3 (exit $2)"; else bad "$3 — expected exit $1, got $2"; fi
}
assert_grep() { # file pattern label
  if grep -Eq "$2" "$1"; then ok "$3"; else bad "$3 — pattern not found: $2"; fi
}
assert_not_grep() {
  if grep -Eq "$2" "$1"; then bad "$3 — pattern unexpectedly found: $2"; else ok "$3"; fi
}
assert_eq() { # expected actual label
  if [[ "$1" == "$2" ]]; then ok "$3"; else bad "$3 — expected '$1', got '$2'"; fi
}

start_mock() { # [MOCK_BREAK]
  local log="$WORK/mock.log"
  : >"$log"
  MOCK_BREAK="${1:-}" python3 "$MOCK" 0 >"$log" 2>"$WORK/mock.err" &
  MOCK_PID=$!
  local i port=""
  for i in $(seq 1 60); do
    port="$(head -1 "$log" 2>/dev/null)"
    [[ "$port" =~ ^[0-9]+$ ]] && break
    sleep 0.1
  done
  if [[ ! "$port" =~ ^[0-9]+$ ]]; then
    printf '%sfatal:%s mock server did not start: %s\n' "$R" "$O" "$(cat "$WORK/mock.err")" >&2
    exit 1
  fi
  MOCK_URL="http://127.0.0.1:$port"
}

stop_mock() {
  [[ -n "$MOCK_PID" ]] && kill "$MOCK_PID" 2>/dev/null
  wait "$MOCK_PID" 2>/dev/null
  MOCK_PID=""
}

# Reads state straight out of the mock, which is how the read-only and restore claims
# get checked independently of anything the harness reports about itself.
mock_get() { curl -sS --max-time 5 "$MOCK_URL$1"; }
mock_balance() {
  mock_get "/api/account/api/v1/accounts/$1" \
    | python3 "$HELPER" get /dev/stdin availableBalance 2>/dev/null
}
mock_balance_of() {
  mock_get "/api/account/api/v1/accounts/$1" >"$WORK/acct.json"
  python3 "$HELPER" money-at "$WORK/acct.json" availableBalance
}
mock_ledger_entries() {
  mock_get "/api/ledger/api/v1/ledger/verify" >"$WORK/verify.json"
  python3 "$HELPER" get "$WORK/verify.json" checkedEntries
}

run_harness() { # outfile args...
  local out="$1"; shift
  bash "$HARNESS" "$MOCK_URL" --admin-url "$MOCK_URL" --direct-ports off --no-color "$@" >"$out" 2>&1
  echo $?
}

FARMER="a2222222-2222-4222-8222-222222222201"
SME="a2222222-2222-4222-8222-222222222202"

# ---------------------------------------------------------------------------
head_ "0 · static checks"
# ---------------------------------------------------------------------------
if bash -n "$HARNESS"; then ok "finix-smoke.sh parses"; else bad "finix-smoke.sh has a syntax error"; fi
if bash -n "${BASH_SOURCE[0]}"; then ok "selftest.sh parses"; else bad "selftest.sh has a syntax error"; fi
if python3 -m py_compile "$HELPER" "$MOCK"; then ok "python helpers compile"; else bad "python helpers do not compile"; fi
# The prohibitions below are checked against code only — the harness's own comments
# discuss -k precisely because it must never appear in an argument list.
grep -vE '^[[:space:]]*#' "$HARNESS" >"$WORK/harness-code.sh"
if grep -Eq '(^|[[:space:]])(-k|--insecure)([[:space:]]|$)' "$WORK/harness-code.sh"; then
  bad "harness contains -k/--insecure"
else
  ok "harness never disables TLS verification"
fi
if grep -q -- '--max-time' "$WORK/harness-code.sh" && grep -q -- '--connect-timeout' "$WORK/harness-code.sh"; then
  ok "harness sets connect and total timeouts"
else
  bad "harness is missing request timeouts"
fi
# Every network call must go through the single http() wrapper that owns those timeouts,
# so no future check can quietly issue an untimed request.
stray="$(grep -E '\bcurl\b' "$WORK/harness-code.sh" \
         | grep -vE 'command -v curl|curl "\$\{args\[@\]\}"|curl exit|curl\.err' | wc -l | tr -d ' ')"
assert_eq "0" "$stray" "every curl invocation goes through the http() wrapper"

# ---------------------------------------------------------------------------
head_ "1 · CLI contract"
# ---------------------------------------------------------------------------
bash "$HARNESS" --help >"$WORK/help.txt" 2>&1; assert_exit 0 $? "--help exits 0"
assert_grep "$WORK/help.txt" "READ-ONLY" "--help documents read-only mode"
assert_grep "$WORK/help.txt" "EXIT CODES" "--help documents exit codes"
bash "$HARNESS" >"$WORK/noargs.txt" 2>&1; assert_exit 3 $? "missing BASE_URL is a usage error"
bash "$HARNESS" ftp://example.org >"$WORK/badscheme.txt" 2>&1; assert_exit 3 $? "non-http scheme is rejected"
bash "$HARNESS" http://127.0.0.1:1 --direct-ports off >"$WORK/down.txt" 2>&1
assert_exit 4 $? "unreachable base URL is a preflight failure"

# ---------------------------------------------------------------------------
head_ "2 · helper units"
# ---------------------------------------------------------------------------
assert_eq "125000" "$(python3 "$HELPER" money 'LKR 1250.00')" "money parses to minor units"
assert_eq "LKR 1.00" "$(python3 "$HELPER" money-str 100)" "minor units render back"
python3 "$HELPER" money 'LKR 1.005' >/dev/null 2>&1; assert_exit 1 $? "sub-cent money is rejected"

# An inclusion proof the harness has never seen, folded by hand with the RFC-6962 rules.
python3 - "$WORK/proof.json" "$WORK/proof-bad.json" <<'PY'
import hashlib, json, sys
def sha(*c):
    d = hashlib.sha256()
    for x in c: d.update(x)
    return d.hexdigest()
leaves = [sha(bytes([i]) * 32) for i in range(1, 6)]
level = [sha(b"\x00", bytes.fromhex(h)) for h in leaves]
levels = [level]
while len(level) > 1:
    level = [sha(b"\x01", bytes.fromhex(level[i]), bytes.fromhex(level[i+1])) if i+1 < len(level) else level[i]
             for i in range(0, len(level), 2)]
    levels.append(level)
idx, path = 2, []
for lv in levels[:-1]:
    sib = idx + 1 if idx % 2 == 0 else idx - 1
    if sib < len(lv):
        path.append({"siblingHash": lv[sib], "isLeftSibling": sib < idx})
    idx //= 2
proof = {"entryHash": leaves[2], "merkleRoot": levels[-1][0], "merklePath": path}
json.dump(proof, open(sys.argv[1], "w"))
bad = dict(proof, merkleRoot="f" * 64)
json.dump(bad, open(sys.argv[2], "w"))
PY
python3 "$HELPER" merkle "$WORK/proof.json" >/dev/null; assert_exit 0 $? "merkle verifier accepts a valid proof"
python3 "$HELPER" merkle "$WORK/proof-bad.json" >/dev/null 2>&1; assert_exit 1 $? "merkle verifier rejects a forged root"

printf '{"access_token":"s3cret-value","note":"Bearer abcdefghijklmnopqrstuvwxyz012345","entryHash":"%s"}' \
  "$(printf 'a%.0s' {1..64})" >"$WORK/secret.json"
python3 "$HELPER" redact "$WORK/secret.json" 400 >"$WORK/redacted.txt"
assert_not_grep "$WORK/redacted.txt" "s3cret-value" "redaction masks token values"
assert_not_grep "$WORK/redacted.txt" "abcdefghijklmnopqrstuvwxyz012345" "redaction masks bearer credentials"
assert_grep "$WORK/redacted.txt" "aaaaaaaa" "redaction keeps sha-256 evidence readable"

# ---------------------------------------------------------------------------
head_ "3 · read-only mode leaves no trace"
# ---------------------------------------------------------------------------
start_mock
farmer_before="$(mock_balance_of "$FARMER")"
sme_before="$(mock_balance_of "$SME")"
rc="$(run_harness "$WORK/ro.txt" --read-only)"
assert_exit 0 "$rc" "read-only run succeeds"
assert_grep "$WORK/ro.txt" "READ-ONLY \(no request other than GET/HEAD\)" "mode banner states read-only"
assert_grep "$WORK/ro.txt" "PASS \[RO \].*web.root" "web application check passes"
assert_grep "$WORK/ro.txt" "PASS \[RO \].*account.balance.farmer" "farmer balance is read"
assert_grep "$WORK/ro.txt" "PASS \[RO \].*ledger.verify" "ledger chain is verified"
assert_not_grep "$WORK/ro.txt" "(PASS|FAIL) \[MUT\]" "no mutating check ran"
assert_grep "$WORK/ro.txt" "SKIP \[MUT\].*transfer.allow.*read-only mode" "transfer is skipped by mode"
assert_eq "$farmer_before" "$(mock_balance_of "$FARMER")" "farmer balance untouched by read-only run"
assert_eq "$sme_before" "$(mock_balance_of "$SME")" "sme balance untouched by read-only run"
assert_eq "0" "$(mock_ledger_entries)" "no ledger entry written by read-only run"
stop_mock

# ---------------------------------------------------------------------------
head_ "4 · full demo run"
# ---------------------------------------------------------------------------
start_mock
farmer_before="$(mock_balance_of "$FARMER")"
sme_before="$(mock_balance_of "$SME")"
rc="$(run_harness "$WORK/full.txt" --mutate --json-report "$WORK/report.json")"
assert_exit 0 "$rc" "full run succeeds"
for check in \
  "transfer.allow" "transfer.idempotent-replay" "idempotency.missing-key" \
  "idempotency.key-reuse" "balance.delta" "ledger.journal" "ledger.verify.after" \
  "ledger.merkle-proof" "risk.step-up" "risk.step-up.resume" "risk.blocked" \
  "offline.device.register" "offline.voucher.settle" "offline.replay.reject" \
  "ussd.menu" "ussd.balance" "loan.apply-decide" "compliance.screen" \
  "payhub.pacs008" "notify.send" "balance.restore"
do
  assert_grep "$WORK/full.txt" "PASS \[MUT\].*${check//./\\.}" "mutating check passes: $check"
done
assert_grep "$WORK/full.txt" "RESULT: OK" "run reports OK"
assert_grep "$WORK/full.txt" "total duration" "run reports total duration"
assert_eq "$farmer_before" "$(mock_balance_of "$FARMER")" "farmer balance restored after full run"
assert_eq "$sme_before" "$(mock_balance_of "$SME")" "sme balance restored after full run"
if [[ -s "$WORK/report.json" ]] && python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$WORK/report.json"; then
  ok "json report is valid JSON"
else
  bad "json report is missing or malformed"
fi

# A second run against the same environment must be just as green — that is the
# rerun-safety claim, tested rather than asserted.
rc="$(run_harness "$WORK/full2.txt" --mutate)"
assert_exit 0 "$rc" "second consecutive run also succeeds"
assert_eq "$farmer_before" "$(mock_balance_of "$FARMER")" "balances still restored after a rerun"
stop_mock

# ---------------------------------------------------------------------------
head_ "5 · injected faults must fail the run"
# ---------------------------------------------------------------------------
start_mock ledger
rc="$(run_harness "$WORK/break-ledger.txt" --mutate)"
assert_exit 1 "$rc" "a broken hash chain fails the run"
assert_grep "$WORK/break-ledger.txt" "FAIL \[RO \].*ledger.verify.*INVALID" "the break is reported as a chain failure"
stop_mock

start_mock account-number
rc="$(run_harness "$WORK/break-account.txt" --mutate)"
assert_exit 1 "$rc" "an account that is not the seeded demo account fails the run"
assert_grep "$WORK/break-account.txt" "FAIL \[RO \].*account.balance.farmer.*mismatch" "the mismatch is reported"
assert_grep "$WORK/break-account.txt" "SKIP \[MUT\].*transfer.allow.*refusing to move money" \
  "the harness refuses to move money it cannot prove is demo data"
stop_mock

start_mock double-debit
rc="$(run_harness "$WORK/break-dupe.txt" --mutate)"
assert_exit 1 "$rc" "a duplicate that debits twice fails the run"
assert_grep "$WORK/break-dupe.txt" "FAIL \[MUT\].*balance.delta" "the double debit is caught by the balance delta"
stop_mock

# ---------------------------------------------------------------------------
head_ "Summary"
# ---------------------------------------------------------------------------
printf '  %d assertions, %d failed\n' "$TESTS" "$FAILURES"
if (( FAILURES == 0 )); then
  printf '  %sSELFTEST OK%s\n' "$G" "$O"
  exit 0
fi
printf '  %sSELFTEST FAILED%s\n' "$R" "$O"
exit 1
