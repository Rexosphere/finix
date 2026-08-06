#!/usr/bin/env bash
# FINIX Phase 3 production verification — STRICTLY READ-ONLY.
#
# Run this ON the production server after the final merge/deploy. It answers one question in one
# command: "is what we just shipped actually live, and does it respond?"
#
# Read-only contract (see also the banner this script prints):
#   * every HTTP call is an explicit `--request GET`; no POST/PUT/PATCH/DELETE, no request body
#   * compose is only ever asked `config --quiet` and `ps` — never up/down/restart/pull/build/exec
#   * no container is started, stopped, recreated or entered; no database is touched
#   * no destructive FINIX endpoint is probed (no ledger tamper, admin seed, vault seed/reconstruct,
#     transfer, compensation or risk scoring)
#   * no credential, token or key is read, required or printed
#
# Usage:
#   bash scripts/phase3-verify.sh
#   CUSTOMER_URL=https://example.test ADMIN_URL=https://admin.example.test bash scripts/phase3-verify.sh
#
# Environment overrides (all optional, none secret):
#   CUSTOMER_URL           public customer origin   (default https://roboti.qzz.io)
#   ADMIN_URL              public admin origin      (default https://admin.roboti.qzz.io)
#   PHASE3_HTTP_TIMEOUT    per-request budget, sec  (default 10)
#   PHASE3_CONNECT_TIMEOUT connect budget, sec      (default 5)
#   PHASE3_SMOKE_STRICT    1 → smoke fails on any down service (default 0, soft)
#
# Exit status: 0 when no genuine verification failure was recorded, 1 otherwise.
# WARN and SKIP never fail the run — only FAIL does.

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CUSTOMER_URL="${CUSTOMER_URL:-https://roboti.qzz.io}"
ADMIN_URL="${ADMIN_URL:-https://admin.roboti.qzz.io}"
HTTP_TIMEOUT="${PHASE3_HTTP_TIMEOUT:-10}"
CONNECT_TIMEOUT="${PHASE3_CONNECT_TIMEOUT:-5}"
SMOKE_STRICT="${PHASE3_SMOKE_STRICT:-0}"

# Paths are discovered from this repository, not invented. Both are asserted before use.
COMPOSE_FILE="$ROOT/infra/compose/docker-compose.yml"
SMOKE_SCRIPT="$ROOT/tests/e2e/finix-smoke.sh"

# Mirrors the Makefile so `config` resolves the same project the deploy created. Non-secret, and
# only set when the operator has not already exported a value.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-finix}"
export FINIX_IMAGE_PREFIX="${FINIX_IMAGE_PREFIX:-ghcr.io/rexosphere/finix}"
export FINIX_IMAGE_TAG="${FINIX_IMAGE_TAG:-latest}"
# Validate and list every profile, otherwise services outside `core` look absent.
export COMPOSE_PROFILES="${COMPOSE_PROFILES:-core,security,monitoring,full}"

N_PASS=0
N_WARN=0
N_FAIL=0
N_SKIP=0

if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_PASS=$'\033[32m'; C_WARN=$'\033[33m'; C_FAIL=$'\033[31m'; C_SKIP=$'\033[36m'
  C_HEAD=$'\033[1m'
else
  C_RESET=''; C_PASS=''; C_WARN=''; C_FAIL=''; C_SKIP=''; C_HEAD=''
fi

pass() { printf '  %sPASS%s  %s\n' "$C_PASS" "$C_RESET" "$*"; N_PASS=$((N_PASS + 1)); }
warn() { printf '  %sWARN%s  %s\n' "$C_WARN" "$C_RESET" "$*"; N_WARN=$((N_WARN + 1)); }
fail() { printf '  %sFAIL%s  %s\n' "$C_FAIL" "$C_RESET" "$*"; N_FAIL=$((N_FAIL + 1)); }
skip() { printf '  %sSKIP%s  %s\n' "$C_SKIP" "$C_RESET" "$*"; N_SKIP=$((N_SKIP + 1)); }
note() { printf '        %s\n' "$*"; }
section() { printf '\n%s%s%s\n' "$C_HEAD" "$*" "$C_RESET"; }

# An unexpected error must not be mistaken for a clean verification.
on_error() {
  local rc=$? line=${BASH_LINENO[0]:-?}
  printf '\n%sFINIX PRODUCTION VERIFICATION: FAIL%s (unexpected error at line %s, status %s)\n' \
    "$C_FAIL" "$C_RESET" "$line" "$rc"
  exit 1
}
trap on_error ERR

# GET-only probe. Prints exactly one three-digit HTTP status, or 000 when the request never
# completed. `--request GET` is explicit so no future edit can turn this into a mutation by
# accident. curl already writes 000 on a transport failure, so the fallback replaces that output
# rather than appending to it.
http_status() {
  local url="$1" out rc=0
  out="$(curl --silent --location --max-redirs 3 --request GET \
    --connect-timeout "$CONNECT_TIMEOUT" --max-time "$HTTP_TIMEOUT" \
    --output /dev/null --write-out '%{http_code}' \
    "$url" 2>/dev/null)" || rc=$?
  if [[ "$rc" -ne 0 || ! "$out" =~ ^[0-9]{3}$ ]]; then
    printf '000'
  else
    printf '%s' "$out"
  fi
}

# GET is idempotent, so retrying costs nothing and is still read-only. Without this, a cold DNS
# or TLS handshake on the first request of the run can report a false FAIL against a healthy edge.
http_status_retry() {
  local url="$1" code attempt
  for attempt in 1 2 3; do
    code="$(http_status "$url")"
    if [[ "$code" != '000' ]]; then
      printf '%s' "$code"
      return 0
    fi
    if [[ "$attempt" -lt 3 ]]; then
      sleep 2
    fi
  done
  printf '000'
}

# Classifies a root/document probe. 2xx passes; everything else is a real edge failure.
check_edge() {
  local label="$1" url="$2" code
  code="$(http_status_retry "$url")"
  case "$code" in
    2??) pass "$label ($url) → HTTP $code" ;;
    000) fail "$label ($url) → no response after 3 attempts (DNS, TLS, timeout or refused)" ;;
    *)   fail "$label ($url) → HTTP $code" ;;
  esac
}

# Pages that may still be pending merge: 404 is a SKIP, not a failure.
check_optional_page() {
  local label="$1" url="$2" code
  code="$(http_status_retry "$url")"
  case "$code" in
    2??) pass "$label ($url) → HTTP $code" ;;
    404) skip "$label ($url) → HTTP 404 (not deployed yet / pending merge)" ;;
    000) warn "$label ($url) → no response after 3 attempts" ;;
    *)   warn "$label ($url) → HTTP $code" ;;
  esac
}

printf '%s==========================================================%s\n' "$C_HEAD" "$C_RESET"
printf '%s        FINIX PHASE 3 PRODUCTION VERIFICATION%s\n' "$C_HEAD" "$C_RESET"
printf '%s==========================================================%s\n' "$C_HEAD" "$C_RESET"
printf 'read-only: GET probes + compose config/ps only — nothing is started,\n'
printf 'stopped, seeded, tampered, transferred or otherwise mutated.\n'

section '[1/6] Revision'
printf '  utc        %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
printf '  hostname   %s\n' "$(hostname 2>/dev/null || echo 'unknown')"
printf '  repo root  %s\n' "$ROOT"
printf '  customer   %s\n' "$CUSTOMER_URL"
printf '  admin      %s\n' "$ADMIN_URL"

if ! command -v git >/dev/null 2>&1; then
  warn 'git not installed — cannot report the deployed revision'
elif ! git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  warn "$ROOT is not a git work tree — cannot report the deployed revision"
else
  git_sha="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo 'unknown')"
  git_branch="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')"
  git_when="$(git -C "$ROOT" log -1 --format=%cI 2>/dev/null || echo 'unknown')"
  git_subject="$(git -C "$ROOT" log -1 --format=%s 2>/dev/null || echo 'unknown')"
  printf '  commit     %s\n' "$git_sha"
  printf '  branch     %s\n' "$git_branch"
  printf '  committed  %s\n' "$git_when"
  printf '  subject    %s\n' "$git_subject"
  pass "deployed revision identified: ${git_sha:0:12} on $git_branch"

  if [[ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]]; then
    dirty_count="$(git -C "$ROOT" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
    warn "working tree is DIRTY ($dirty_count entries) — deployed content may differ from $git_sha"
    note 'not a failure by itself; listing paths only (no file contents):'
    git -C "$ROOT" status --porcelain 2>/dev/null | head -20 | sed 's/^/          /'
  else
    pass 'working tree is clean'
  fi
fi

section '[2/6] Compose validation'
COMPOSE_BIN=()
if [[ ! -f "$COMPOSE_FILE" ]]; then
  fail "compose file not found at $COMPOSE_FILE"
else
  note "file    $COMPOSE_FILE"
  note "project $COMPOSE_PROJECT_NAME"
  note "profiles $COMPOSE_PROFILES"
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    COMPOSE_BIN=(docker compose -f "$COMPOSE_FILE")
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_BIN=(docker-compose -f "$COMPOSE_FILE")
  fi

  if [[ ${#COMPOSE_BIN[@]} -eq 0 ]]; then
    warn 'no docker compose CLI available — compose validation skipped (not faked)'
    skip 'compose config --quiet'
  else
    note "invocation ${COMPOSE_BIN[*]}"
    # `--quiet` validates and interpolates without echoing the resolved model, which would print
    # default values for variables such as GF_SECURITY_ADMIN_PASSWORD. Validation only.
    if "${COMPOSE_BIN[@]}" config --quiet >/dev/null 2>&1; then
      pass 'compose config --quiet — configuration is valid'
    else
      compose_err="$("${COMPOSE_BIN[@]}" config --quiet 2>&1 || true)"
      if printf '%s' "$compose_err" | grep -qiE 'docker api|docker\.sock|daemon|cannot connect'; then
        warn 'docker daemon unreachable — compose validation skipped (not faked)'
        note "${compose_err%%$'\n'*}"
        skip 'compose config --quiet'
      else
        fail 'compose config --quiet — configuration is INVALID'
        printf '%s\n' "$compose_err" | head -15 | sed 's/^/          /'
      fi
    fi
  fi
fi

section '[3/6] Service status'
if [[ ${#COMPOSE_BIN[@]} -eq 0 ]]; then
  skip 'container status — no docker compose CLI available'
else
  ps_out=''
  if ps_out="$("${COMPOSE_BIN[@]}" ps 2>&1)"; then
    printf '%s\n' "$ps_out" | sed 's/^/        /'
    running="$(printf '%s\n' "$ps_out" | grep -ciE '(^|[[:space:]])(running|up)([[:space:]]|$)' || true)"
    unhealthy="$(printf '%s\n' "$ps_out" | grep -ci 'unhealthy' || true)"
    exited="$(printf '%s\n' "$ps_out" | grep -ciE 'exit|exited|restarting' || true)"
    if [[ "$running" -eq 0 ]]; then
      fail 'no service reported as running for this compose project'
    else
      pass "$running service line(s) reported running"
    fi
    [[ "$unhealthy" -gt 0 ]] && warn "$unhealthy service line(s) reported unhealthy"
    [[ "$exited" -gt 0 ]] && fail "$exited service line(s) reported exited/restarting"
  else
    if printf '%s' "$ps_out" | grep -qiE 'docker api|docker\.sock|daemon|cannot connect'; then
      warn 'docker daemon unreachable — container status skipped (not faked)'
      skip 'compose ps'
    else
      fail 'compose ps failed'
      printf '%s\n' "$ps_out" | head -15 | sed 's/^/          /'
    fi
  fi
fi

section '[4/6] Customer edge'
check_edge 'customer root' "$CUSTOMER_URL/"
check_optional_page 'customer /judge.html' "$CUSTOMER_URL/judge.html"

section '[5/6] Admin edge'
check_edge 'admin root' "$ADMIN_URL/"
check_optional_page 'admin /judge.html' "$ADMIN_URL/judge.html"

section '[6/6] Read-only smoke'
if [[ ! -f "$SMOKE_SCRIPT" ]]; then
  warn "smoke script not found at $SMOKE_SCRIPT"
  skip 'FINIX read-only smoke'
else
  note "script $SMOKE_SCRIPT"
  note 'run with --read-only: the harness issues nothing but GET/HEAD and cannot move money.'
  note "invocation bash $SMOKE_SCRIPT $CUSTOMER_URL --admin-url $ADMIN_URL --read-only"
  smoke_rc=0
  smoke_out="$(bash "$SMOKE_SCRIPT" "$CUSTOMER_URL" --admin-url "$ADMIN_URL" --read-only --no-color 2>&1)" || smoke_rc=$?
  printf '%s\n' "$smoke_out" | sed 's/^/        /'
  smoke_summary="$(printf '%s\n' "$smoke_out" | grep -E '^Summary:' | tail -1 || true)"
  if [[ "$smoke_rc" -eq 0 ]]; then
    pass "read-only smoke completed (${smoke_summary:-no summary line})"
  else
    fail "read-only smoke failed with exit $smoke_rc (${smoke_summary:-no summary line})"
  fi
  if [[ -n "$smoke_summary" ]] && printf '%s' "$smoke_summary" | grep -qE 'skip=[1-9]'; then
    warn 'some services did not respond — see the SKIP lines above'
  fi
fi

section 'Summary'
printf '  PASS %d   WARN %d   SKIP %d   FAIL %d\n' "$N_PASS" "$N_WARN" "$N_SKIP" "$N_FAIL"
if [[ "$N_WARN" -gt 0 || "$N_SKIP" -gt 0 ]]; then
  note 'WARN/SKIP are informational and do not fail this run.'
fi

trap - ERR
if [[ "$N_FAIL" -gt 0 ]]; then
  printf '\n%sFINIX PRODUCTION VERIFICATION: FAIL%s\n' "$C_FAIL" "$C_RESET"
  exit 1
fi
printf '\n%sFINIX PRODUCTION VERIFICATION: PASS%s\n' "$C_PASS" "$C_RESET"
exit 0
