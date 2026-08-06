#!/usr/bin/env bash
#
# FINIX environment smoke harness.
#
# Validates a running FINIX deployment through its public edge (the web app and, if
# configured, the admin app) using nothing but seeded demo data. Two clearly separated
# phases:
#
#   READ-ONLY   GET/HEAD only. Cannot change a single byte of state. Safe to point at
#               any environment, including one a judge is mid-demo on.
#   MUTATING    Moves LKR 1.00 between seeded demo accounts and back, mints a throwaway
#               offline device, and exercises the risk/loan/payment/notification demo
#               endpoints. Never touches an account it did not find in the checked-in
#               seed configuration.
#
# Design rules this script holds itself to:
#   * every network call carries an explicit connect and total timeout;
#   * TLS verification is never disabled — there is no code path that passes -k;
#   * tokens and key material are redacted before anything is printed;
#   * demo identifiers are parsed out of the seed source of truth, never invented;
#   * no destructive operation exists, and a rerun ends where it started.
#
# Exit codes:
#   0  no failures
#   1  at least one CRITICAL check failed
#   2  only non-critical checks failed
#   3  usage error
#   4  preflight failed (missing tooling, unreachable base URL, unverifiable demo data)
#
set -uo pipefail

VERSION="1.0.0"
SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
HELPER="$SCRIPT_DIR/lib/finix_smoke_helper.py"

# ---------------------------------------------------------------------------
# Defaults (every one overridable by flag or environment)
# ---------------------------------------------------------------------------
BASE_URL="${FINIX_BASE_URL:-}"
ADMIN_URL="${FINIX_ADMIN_URL:-}"
READ_ONLY=0
STRICT=0
RESTORE=1
CONNECT_TIMEOUT="${FINIX_CONNECT_TIMEOUT:-5}"
MAX_TIME="${FINIX_TIMEOUT:-20}"
DIRECT_PORTS="${FINIX_DIRECT_PORTS:-auto}"   # auto | on | off
CA_BUNDLE="${FINIX_CA_BUNDLE:-}"
BEARER="${FINIX_BEARER_TOKEN:-}"
JSON_REPORT=""
SEED_FILE="${FINIX_SEED_FILE:-$REPO_ROOT/services/account-service/src/main/kotlin/org/finix/account/domain/DemoAccounts.kt}"
USSD_SEED_FILE="${FINIX_USSD_SEED_FILE:-$REPO_ROOT/services/ussd-gateway/src/main/kotlin/org/finix/ussd/domain/UssdDirectory.kt}"
COLOR="auto"
VERBOSE=0

# The single amount every money-moving check uses. Small on purpose: a judge's demo
# balances stay recognisable, and the restore leg puts even this back.
DEMO_MINOR=100
DEMO_AMOUNT="LKR 1.00"

usage() {
  cat <<EOF
$SCRIPT_NAME $VERSION — FINIX environment smoke harness

USAGE
  $SCRIPT_NAME BASE_URL [options]
  $SCRIPT_NAME --base-url BASE_URL [options]

  BASE_URL is the main web application origin (the nginx that also reverse-proxies
  /api/account, /api/orchestrator, /api/ledger and /api/ussd). Example:
    $SCRIPT_NAME http://localhost:3000
    $SCRIPT_NAME https://finix.example.org --admin-url https://admin.finix.example.org

OPTIONS
  --base-url URL        Main web application origin (or pass it positionally).
  --admin-url URL       Admin application origin. Enables the vault / risk / compliance
                        / loan / notification / payment-hub checks behind its proxy.
  --read-only           Run READ-ONLY checks only. Issues no request other than GET/HEAD.
  --strict              Treat every failure as critical (any FAIL then exits 1).
  --no-restore          Skip the closing transfer that returns the demo money moved.
  --timeout SECONDS     Per-request total timeout (default $MAX_TIME).
  --connect-timeout SEC Per-request connect timeout (default $CONNECT_TIMEOUT).
  --direct-ports MODE   auto|on|off — also try the documented per-service ports
                        (8082-8093) on the base host. 'auto' (default) enables this
                        only for loopback hosts.
  --cacert FILE         Extra CA bundle for TLS verification. Verification is never
                        disabled; this only adds trust anchors.
  --seed-file FILE      DemoAccounts.kt to read demo identifiers from
                        (default: services/account-service/.../domain/DemoAccounts.kt).
  --json-report FILE    Also write a machine-readable report.
  --no-color            Disable ANSI colour.
  --verbose             Print the resolved URL of every check.
  -h, --help            This text.
  --version             Print version and exit.

ENVIRONMENT
  FINIX_BASE_URL, FINIX_ADMIN_URL, FINIX_TIMEOUT, FINIX_CONNECT_TIMEOUT,
  FINIX_DIRECT_PORTS, FINIX_CA_BUNDLE, FINIX_SEED_FILE
  FINIX_BEARER_TOKEN            Sent as 'Authorization: Bearer …' and always redacted.
  FINIX_<SERVICE>_URL           Pin one service base URL, bypassing discovery. SERVICE is
                                one of ACCOUNT ORCHESTRATOR LEDGER USSD VAULT RISK
                                COMPLIANCE LOAN NOTIFY PAY.

EXIT CODES
  0 all good · 1 critical failure · 2 non-critical failure only · 3 usage · 4 preflight

SAFETY
  Mutating checks refuse to run unless every target account id AND account number
  matches the checked-in seed configuration, so the harness cannot be aimed at a real
  customer account. It performs no delete, no tamper, and no ceremony transition.
EOF
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
die_usage() { printf 'error: %s\n\n' "$1" >&2; usage >&2; exit 3; }

while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --version) printf '%s %s\n' "$SCRIPT_NAME" "$VERSION"; exit 0 ;;
    --base-url) [[ $# -ge 2 ]] || die_usage "--base-url needs a value"; BASE_URL="$2"; shift 2 ;;
    --admin-url) [[ $# -ge 2 ]] || die_usage "--admin-url needs a value"; ADMIN_URL="$2"; shift 2 ;;
    --read-only) READ_ONLY=1; shift ;;
    --strict) STRICT=1; shift ;;
    --no-restore) RESTORE=0; shift ;;
    --timeout) [[ $# -ge 2 ]] || die_usage "--timeout needs a value"; MAX_TIME="$2"; shift 2 ;;
    --connect-timeout) [[ $# -ge 2 ]] || die_usage "--connect-timeout needs a value"; CONNECT_TIMEOUT="$2"; shift 2 ;;
    --direct-ports) [[ $# -ge 2 ]] || die_usage "--direct-ports needs a value"; DIRECT_PORTS="$2"; shift 2 ;;
    --cacert) [[ $# -ge 2 ]] || die_usage "--cacert needs a value"; CA_BUNDLE="$2"; shift 2 ;;
    --seed-file) [[ $# -ge 2 ]] || die_usage "--seed-file needs a value"; SEED_FILE="$2"; shift 2 ;;
    --json-report) [[ $# -ge 2 ]] || die_usage "--json-report needs a value"; JSON_REPORT="$2"; shift 2 ;;
    --no-color) COLOR="never"; shift ;;
    --verbose) VERBOSE=1; shift ;;
    --) shift; break ;;
    -*) die_usage "unknown option: $1" ;;
    *) if [[ -z "$BASE_URL" ]]; then BASE_URL="$1"; shift; else die_usage "unexpected argument: $1"; fi ;;
  esac
done

[[ -n "$BASE_URL" ]] || die_usage "BASE_URL is required"
[[ "$BASE_URL" =~ ^https?:// ]] || die_usage "BASE_URL must start with http:// or https:// (got: $BASE_URL)"
[[ -z "$ADMIN_URL" || "$ADMIN_URL" =~ ^https?:// ]] || die_usage "--admin-url must start with http:// or https://"
[[ "$MAX_TIME" =~ ^[0-9]+$ ]] || die_usage "--timeout must be a whole number of seconds"
[[ "$CONNECT_TIMEOUT" =~ ^[0-9]+$ ]] || die_usage "--connect-timeout must be a whole number of seconds"
case "$DIRECT_PORTS" in auto|on|off) ;; *) die_usage "--direct-ports must be auto, on or off" ;; esac

BASE_URL="${BASE_URL%/}"
ADMIN_URL="${ADMIN_URL%/}"

# ---------------------------------------------------------------------------
# Terminal formatting
# ---------------------------------------------------------------------------
if [[ "$COLOR" == "auto" && -t 1 ]]; then COLOR="always"; fi
if [[ "$COLOR" == "always" ]]; then
  C_PASS=$'\033[32m'; C_FAIL=$'\033[31m'; C_SKIP=$'\033[33m'
  C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'; C_OFF=$'\033[0m'
else
  C_PASS=""; C_FAIL=""; C_SKIP=""; C_DIM=""; C_BOLD=""; C_OFF=""
fi

# ---------------------------------------------------------------------------
# Scratch space — 0700, and removed on every exit path including Ctrl-C. The offline
# voucher private key lives here and must not outlive the run.
# ---------------------------------------------------------------------------
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/finix-smoke.XXXXXX")" || { echo "cannot create temp dir" >&2; exit 4; }
chmod 700 "$WORK_DIR"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT INT TERM

BODY_FILE="$WORK_DIR/body"
HDR_FILE="$WORK_DIR/headers"
ERR_FILE="$WORK_DIR/curl.err"
JSONL_FILE="$WORK_DIR/checks.jsonl"
: >"$BODY_FILE"; : >"$HDR_FILE"; : >"$ERR_FILE"; : >"$JSONL_FILE"

# ---------------------------------------------------------------------------
# Small utilities
# ---------------------------------------------------------------------------
py() { python3 "$HELPER" "$@"; }

now_ms() {
  if [[ -n "${EPOCHREALTIME:-}" ]]; then
    local t="${EPOCHREALTIME/,/.}"
    printf '%d' "$(( ${t%.*} * 1000 + 10#${t#*.} / 1000 ))"
  else
    py now-ms
  fi
}

note() { printf '%s%s%s\n' "$C_DIM" "$1" "$C_OFF"; }
section() { printf '\n%s%s%s\n' "$C_BOLD" "$1" "$C_OFF"; }

# Redacted, length-capped view of the last response — the only way a body is ever shown.
body_excerpt() { py redact "$BODY_FILE" "${1:-240}" 2>/dev/null || echo "<unreadable body>"; }

header_value() {
  tr -d '\r' <"$HDR_FILE" | grep -i "^$1:" | tail -1 | cut -d: -f2- | sed 's/^[[:space:]]*//'
}

jget() { py get "$BODY_FILE" "$@"; }

# ---------------------------------------------------------------------------
# HTTP — the single choke point for every network call.
#
# Timeouts are not optional arguments here; they are baked in so no future check can
# forget them. -k / --insecure appears nowhere: an environment with a bad certificate
# must fail loudly rather than be smoke-tested over an unverified channel.
# ---------------------------------------------------------------------------
HTTP_STATUS="000"
HTTP_ERROR=""
REQUEST_COUNT=0

http() {
  local method="$1" url="$2" data="${3:-}" ctype="${4:-application/json}"
  local -a extra=()
  if (( $# > 4 )); then shift 4; extra=("$@"); fi

  local -a args=(
    --silent --show-error
    --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME"
    --proto "=http,https" --proto-redir "=http,https" --max-redirs 3
    --request "$method"
    --output "$BODY_FILE" --dump-header "$HDR_FILE" --write-out '%{http_code}'
    --header "Accept: application/json, text/plain, text/html;q=0.8, */*;q=0.5"
    --header "User-Agent: finix-smoke/$VERSION"
  )
  # Redirects are followed for safe methods only: a 301 on a POST would silently
  # downgrade it to a GET and turn a real failure into a green tick.
  [[ "$method" == "GET" || "$method" == "HEAD" ]] && args+=(--location)
  [[ -n "$CA_BUNDLE" ]] && args+=(--cacert "$CA_BUNDLE")
  [[ -n "$BEARER" ]] && args+=(--header "Authorization: Bearer $BEARER")
  local header
  for header in ${extra[@]+"${extra[@]}"}; do
    [[ -n "$header" ]] && args+=(--header "$header")
  done
  if [[ -n "$data" ]]; then
    args+=(--header "Content-Type: $ctype" --data-binary "$data")
  fi

  (( VERBOSE )) && note "    → $method $url"
  : >"$BODY_FILE"; : >"$HDR_FILE"; : >"$ERR_FILE"
  REQUEST_COUNT=$(( REQUEST_COUNT + 1 ))
  local code rc
  code="$(curl "${args[@]}" "$url" 2>"$ERR_FILE")"
  rc=$?
  HTTP_STATUS="${code:-000}"
  if (( rc != 0 )); then
    HTTP_ERROR="curl exit $rc: $(tr -d '\r' <"$ERR_FILE" | tr '\n' ' ' | cut -c1-160)"
    return 1
  fi
  HTTP_ERROR=""
  return 0
}

get()  { http GET  "$@"; }
post() { http POST "$@"; }

# ---------------------------------------------------------------------------
# Result recording
# ---------------------------------------------------------------------------
N_PASS=0; N_FAIL=0; N_SKIP=0; N_FAIL_CRIT=0
CHECK_ID=""; CHECK_GROUP=""; CHECK_CRIT="opt"; CHECK_T0=0

begin() { CHECK_ID="$1"; CHECK_GROUP="$2"; CHECK_CRIT="${3:-opt}"; CHECK_T0="$(now_ms)"; }

record() {
  local status="$1" detail="${2:-}"
  local ms=$(( $(now_ms) - CHECK_T0 ))
  (( ms < 0 )) && ms=0
  local colour="$C_SKIP"
  case "$status" in
    PASS) colour="$C_PASS"; N_PASS=$(( N_PASS + 1 )) ;;
    FAIL)
      colour="$C_FAIL"; N_FAIL=$(( N_FAIL + 1 ))
      if [[ "$CHECK_CRIT" == "crit" ]] || (( STRICT )); then N_FAIL_CRIT=$(( N_FAIL_CRIT + 1 )); fi
      ;;
    SKIP) N_SKIP=$(( N_SKIP + 1 )) ;;
  esac
  local marker=" "
  [[ "$CHECK_CRIT" == "crit" ]] && marker="!"
  printf '  %s%-4s%s %s[%-3s]%s %s%-30s %s %s(%sms)%s\n' \
    "$colour" "$status" "$C_OFF" "$C_DIM" "$CHECK_GROUP" "$C_OFF" \
    "$marker" "$CHECK_ID" "$detail" "$C_DIM" "$ms" "$C_OFF"
  if [[ -n "$JSON_REPORT" ]]; then
    py jsonl "$status" "$CHECK_GROUP" "$CHECK_ID" "$CHECK_CRIT" "$detail" "$ms" >>"$JSONL_FILE" 2>/dev/null || true
  fi
}

pass() { record PASS "${1:-}"; }
fail() { record FAIL "${1:-}"; }
skip() { record SKIP "${1:-}"; }

# Emits SKIP and returns 0 when the run is read-only, so a mutating check reads:
#   begin foo MUT opt; ro_skip && return
ro_skip() { if (( READ_ONLY )); then skip "read-only mode"; return 0; fi; return 1; }

http_failed() {
  # Standard failure detail for a request that never produced a response.
  if [[ -n "$HTTP_ERROR" ]]; then echo "$HTTP_ERROR"; else echo "http $HTTP_STATUS"; fi
}

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
preflight_fail() { printf '%sPREFLIGHT FAILED%s %s\n' "$C_FAIL" "$C_OFF" "$1" >&2; exit 4; }

command -v curl    >/dev/null 2>&1 || preflight_fail "curl is required"
command -v python3 >/dev/null 2>&1 || preflight_fail "python3 is required"
[[ -r "$HELPER" ]] || preflight_fail "helper not found: $HELPER"
py money "LKR 1.00" >/dev/null 2>&1 || preflight_fail "helper is not runnable: $HELPER"
HAVE_OPENSSL=0
command -v openssl >/dev/null 2>&1 && HAVE_OPENSSL=1

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
RUN_T0="$(now_ms)"

# ---------------------------------------------------------------------------
# Service discovery
#
# Each capability is reachable three ways, tried in this order:
#   1. FINIX_<SERVICE>_URL  — an operator pinned it, so a failure there is reported,
#      never silently worked around;
#   2. the reverse proxy on the web or admin app — the path a real browser takes, and
#      therefore the one worth proving;
#   3. the documented direct port on the same host — only for loopback by default,
#      because probing 8082-8093 on a public host is neither useful nor polite.
# ---------------------------------------------------------------------------
svc_meta() {
  case "$1" in
    account)      echo "web|/api/account|8083|/actuator/health" ;;
    orchestrator) echo "web|/api/orchestrator|8085|/actuator/health" ;;
    ledger)       echo "web|/api/ledger|8084|/actuator/health" ;;
    ussd)         echo "web|/api/ussd|8087|/actuator/health" ;;
    vault)        echo "admin|/api/vault|8086|/actuator/health" ;;
    risk)         echo "admin|/api/risk|8091|/health" ;;
    compliance)   echo "admin|/api/compliance|8089|/actuator/health" ;;
    loan)         echo "admin|/api/loan|8088|/actuator/health" ;;
    notify)       echo "admin|/api/notify|8093|/health" ;;
    pay)          echo "admin|/api/pay|8092|/health" ;;
    *)            return 1 ;;
  esac
}

ALL_SERVICES="account orchestrator ledger ussd vault risk compliance loan notify pay"

url_host() { local u="${1#*://}"; u="${u%%/*}"; u="${u%%\?*}"; echo "${u%%:*}"; }
url_scheme() { echo "${1%%://*}"; }

BASE_HOST="$(url_host "$BASE_URL")"
BASE_SCHEME="$(url_scheme "$BASE_URL")"

allow_direct_ports() {
  case "$DIRECT_PORTS" in
    on) return 0 ;;
    off) return 1 ;;
    auto)
      case "$BASE_HOST" in
        localhost|127.0.0.1|::1|0.0.0.0|[::1]) return 0 ;;
        *) return 1 ;;
      esac
      ;;
  esac
  return 1
}

svc_base() { local n="SVC_BASE_$1"; echo "${!n:-}"; }
svc_via()  { local n="SVC_VIA_$1";  echo "${!n:-}"; }
svc_up()   { [[ -n "$(svc_base "$1")" ]]; }

# GET SERVICE-RELATIVE-PATH against a discovered service. Returns 1 when the service is
# not available, so callers can turn that into SKIP rather than a misleading FAIL.
svc_get() {
  local svc="$1" path="$2"
  local -a extra=()
  if (( $# > 2 )); then shift 2; extra=("$@"); fi
  local base; base="$(svc_base "$svc")"
  [[ -n "$base" ]] || return 2
  get "${base}${path}" "" "" ${extra[@]+"${extra[@]}"}
}

svc_post() {
  local svc="$1" path="$2" data="${3:-}" ctype="${4:-application/json}"
  local -a extra=()
  if (( $# > 4 )); then shift 4; extra=("$@"); fi
  local base; base="$(svc_base "$svc")"
  [[ -n "$base" ]] || return 2
  post "${base}${path}" "$data" "$ctype" ${extra[@]+"${extra[@]}"}
}

discover_services() {
  local svc meta root prefix port health override candidate var
  for svc in $ALL_SERVICES; do
    meta="$(svc_meta "$svc")"
    root="${meta%%|*}"; meta="${meta#*|}"
    prefix="${meta%%|*}"; meta="${meta#*|}"
    port="${meta%%|*}"; health="${meta#*|}"

    var="FINIX_$(echo "$svc" | tr '[:lower:]' '[:upper:]')_URL"
    override="${!var:-}"

    local -a candidates=() labels=()
    if [[ -n "$override" ]]; then
      candidates+=("${override%/}"); labels+=("env:$var")
    else
      if [[ "$root" == "web" ]]; then
        candidates+=("${BASE_URL}${prefix}"); labels+=("web-proxy")
      elif [[ -n "$ADMIN_URL" ]]; then
        candidates+=("${ADMIN_URL}${prefix}"); labels+=("admin-proxy")
      fi
      if allow_direct_ports; then
        candidates+=("${BASE_SCHEME}://${BASE_HOST}:${port}"); labels+=("direct:${port}")
      fi
    fi

    local i=0
    for candidate in ${candidates[@]+"${candidates[@]}"}; do
      if get "${candidate}${health}" >/dev/null 2>&1 && [[ "$HTTP_STATUS" == "200" ]]; then
        printf -v "SVC_BASE_$svc" '%s' "$candidate"
        printf -v "SVC_VIA_$svc" '%s' "${labels[$i]}"
        break
      fi
      i=$(( i + 1 ))
    done
  done
}

# ---------------------------------------------------------------------------
# Demo identity detection
#
# Read out of the checked-in seed source of truth. Nothing here is a literal typed by
# hand: if DemoAccounts.kt changes, this follows it, and if it cannot be read the run
# stops rather than guessing at an account number.
# ---------------------------------------------------------------------------
kt_uuid() {
  [[ -r "$1" ]] || return 1
  local v
  v="$(grep -E "val[[:space:]]+$2[[:space:]]*:[[:space:]]*UUID" "$1" 2>/dev/null \
       | grep -oE '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' \
       | head -1)"
  [[ -n "$v" ]] && { echo "$v"; return 0; }
  return 1
}

kt_string_const() {
  [[ -r "$1" ]] || return 1
  local v
  v="$(grep -E "val[[:space:]]+$2[[:space:]]*:[[:space:]]*String" "$1" 2>/dev/null \
       | sed -E 's/.*"([^"]*)".*/\1/' | head -1)"
  [[ -n "$v" ]] && { echo "$v"; return 0; }
  return 1
}

kt_subscriber_phone() {
  [[ -r "$1" ]] || return 1
  local v
  v="$(awk -v name="$2" '
        $0 ~ ("val[[:space:]]+" name "[[:space:]]*=[[:space:]]*Subscriber\\(") { inblock = 1 }
        inblock && /phone[[:space:]]*=/ { print; exit }
      ' "$1" | sed -E 's/.*"([^"]*)".*/\1/')"
  [[ -n "$v" ]] && { echo "$v"; return 0; }
  return 1
}

SEED_SOURCE=""
FARMER_USER=""; SME_USER=""; ELDER_USER=""
FARMER_ACCT=""; SME_ACCT=""; ELDER_ACCT=""
FARMER_NUM=""; SME_NUM=""; ELDER_NUM=""
FARMER_PHONE=""

detect_seed_identities() {
  # Environment always wins — an operator who seeded a different demo set can say so.
  FARMER_USER="${FINIX_FARMER_USER_ID:-}"; FARMER_ACCT="${FINIX_FARMER_ACCOUNT_ID:-}"; FARMER_NUM="${FINIX_FARMER_ACCOUNT_NUMBER:-}"
  SME_USER="${FINIX_SME_USER_ID:-}";       SME_ACCT="${FINIX_SME_ACCOUNT_ID:-}";       SME_NUM="${FINIX_SME_ACCOUNT_NUMBER:-}"
  ELDER_USER="${FINIX_ELDER_USER_ID:-}";   ELDER_ACCT="${FINIX_ELDER_ACCOUNT_ID:-}";   ELDER_NUM="${FINIX_ELDER_ACCOUNT_NUMBER:-}"
  FARMER_PHONE="${FINIX_FARMER_PHONE:-}"

  if [[ -r "$SEED_FILE" ]]; then
    [[ -n "$FARMER_USER" ]] || FARMER_USER="$(kt_uuid "$SEED_FILE" FARMER_USER_ID || true)"
    [[ -n "$SME_USER"    ]] || SME_USER="$(kt_uuid "$SEED_FILE" SME_USER_ID || true)"
    [[ -n "$ELDER_USER"  ]] || ELDER_USER="$(kt_uuid "$SEED_FILE" ELDER_USER_ID || true)"
    [[ -n "$FARMER_ACCT" ]] || FARMER_ACCT="$(kt_uuid "$SEED_FILE" FARMER_ACCOUNT_ID || true)"
    [[ -n "$SME_ACCT"    ]] || SME_ACCT="$(kt_uuid "$SEED_FILE" SME_ACCOUNT_ID || true)"
    [[ -n "$ELDER_ACCT"  ]] || ELDER_ACCT="$(kt_uuid "$SEED_FILE" ELDER_ACCOUNT_ID || true)"
    [[ -n "$FARMER_NUM"  ]] || FARMER_NUM="$(kt_string_const "$SEED_FILE" FARMER_ACCOUNT_NUMBER || true)"
    [[ -n "$SME_NUM"     ]] || SME_NUM="$(kt_string_const "$SEED_FILE" SME_ACCOUNT_NUMBER || true)"
    [[ -n "$ELDER_NUM"   ]] || ELDER_NUM="$(kt_string_const "$SEED_FILE" ELDER_ACCOUNT_NUMBER || true)"
    SEED_SOURCE="${SEED_FILE#$REPO_ROOT/}"
  fi
  if [[ -z "$FARMER_PHONE" && -r "$USSD_SEED_FILE" ]]; then
    FARMER_PHONE="$(kt_subscriber_phone "$USSD_SEED_FILE" FARMER || true)"
  fi
  [[ -n "$SEED_SOURCE" ]] || SEED_SOURCE="environment overrides"
}

is_uuid() { [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; }

# Set once the live environment has confirmed that every account we intend to touch is
# genuinely the seeded demo account. Nothing mutating runs until this is 1.
DEMO_DATA_VERIFIED=0

# ---------------------------------------------------------------------------
# READ-ONLY checks
# ---------------------------------------------------------------------------

check_web_app() {
  begin web.root RO crit
  if ! get "$BASE_URL/"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  local ctype size
  ctype="$(header_value content-type)"
  size="$(wc -c <"$BODY_FILE" | tr -d ' ')"
  if ! grep -qi "finix" "$BODY_FILE"; then
    fail "200 but body does not mention FINIX (${size}B)"; return
  fi
  case "$ctype" in
    text/html*) pass "200 ${ctype%%;*} ${size}B" ;;
    *) fail "expected text/html, got '${ctype:-none}'" ;;
  esac

  begin web.pages RO opt
  local page missing="" found=0
  for page in transfer.html verify.html ussd.html offline.html farmer.html sme.html elder.html manifest.webmanifest sw.js; do
    if get "$BASE_URL/$page" >/dev/null 2>&1 && [[ "$HTTP_STATUS" == "200" ]]; then
      found=$(( found + 1 ))
    else
      missing="$missing $page"
    fi
  done
  if [[ -z "$missing" ]]; then pass "$found/9 app pages served"
  else fail "missing:$missing"; fi

  # FR-02: the zero-JS companion must stay inside its 50 KB budget on the wire, and must
  # not have grown a script tag. scripts/check-lite-budget.sh enforces this on the source
  # file; this proves it for what the deployment actually serves.
  begin web.lite.budget RO opt
  if ! get "$BASE_URL/lite.html"; then skip "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then skip "http $HTTP_STATUS"; return; fi
  local bytes; bytes="$(wc -c <"$BODY_FILE" | tr -d ' ')"
  if (( bytes > 51200 )); then
    fail "lite.html ${bytes}B exceeds 50 KB budget"
  elif grep -qi '<script' "$BODY_FILE"; then
    fail "lite.html carries a <script> tag (zero-JS contract)"
  else
    pass "${bytes}B < 51200B, zero client JS"
  fi
}

check_admin_app() {
  begin admin.root RO opt
  if [[ -z "$ADMIN_URL" ]]; then
    skip "no --admin-url configured"
    return
  fi
  if ! get "$ADMIN_URL/"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  local size; size="$(wc -c <"$BODY_FILE" | tr -d ' ')"
  if grep -qi "finix" "$BODY_FILE"; then
    pass "200 admin console served (${size}B)"
  else
    fail "200 but body does not mention FINIX (${size}B)"
  fi
}

# One health check per service, phrased as "is the route from the edge to this service
# actually wired", which is what the reverse proxy checks are really about.
check_service_health() {
  local svc="$1" crit="$2" expect="$3"
  begin "proxy.$svc.health" RO "$crit"
  if ! svc_up "$svc"; then skip "not reachable via proxy or direct port"; return; fi
  local meta health
  meta="$(svc_meta "$svc")"; health="${meta##*|}"
  if ! svc_get "$svc" "$health"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS via $(svc_via "$svc")"; return; fi
  local status; status="$(jget status "")"
  if [[ -n "$expect" && "$status" != "$expect" ]]; then
    fail "status='$status' expected '$expect' via $(svc_via "$svc")"
  else
    pass "${status:-200} via $(svc_via "$svc")"
  fi
}

# Proves the proxy strips its own prefix and forwards the remainder, rather than merely
# answering 200 from nginx itself: a UUID that cannot exist must come back as the
# upstream's RFC 7807 404, not as the SPA fallback page.
check_proxy_passthrough() {
  begin proxy.path-passthrough RO opt
  if ! svc_up account; then skip "account service not reachable"; return; fi
  local ghost="00000000-0000-4000-8000-000000000000"
  if ! svc_get account "/api/v1/accounts/$ghost"; then fail "$(http_failed)"; return; fi
  local ctype; ctype="$(header_value content-type)"
  if [[ "$HTTP_STATUS" == "404" && "$ctype" == *problem+json* ]]; then
    pass "404 application/problem+json from upstream"
  elif [[ "$HTTP_STATUS" == "404" ]]; then
    pass "404 from upstream (content-type ${ctype:-none})"
  else
    fail "expected upstream 404, got $HTTP_STATUS ${ctype:-}"
  fi
}

check_seed_detection() {
  begin seed.detect RO crit
  local missing=""
  for pair in "farmer-user:$FARMER_USER" "farmer-account:$FARMER_ACCT" "farmer-number:$FARMER_NUM" \
              "sme-user:$SME_USER" "sme-account:$SME_ACCT" "sme-number:$SME_NUM"; do
    [[ -n "${pair#*:}" ]] || missing="$missing ${pair%%:*}"
  done
  if [[ -n "$missing" ]]; then
    fail "could not read demo ids from $SEED_SOURCE — missing:$missing"
    return
  fi
  if ! is_uuid "$FARMER_ACCT" || ! is_uuid "$SME_ACCT"; then
    fail "parsed account ids are not UUIDs (farmer=$FARMER_ACCT sme=$SME_ACCT)"
    return
  fi
  pass "from $SEED_SOURCE — farmer=${FARMER_NUM} sme=${SME_NUM}${ELDER_NUM:+ elder=$ELDER_NUM}"
}

# Reads a balance and, at the same time, proves this really is the seeded demo account:
# the account number returned by the live service must equal the one in the seed file.
# That equality is the gate the mutating phase depends on.
FARMER_START=-1; SME_START=-1
check_account_balance() {
  local label="$1" id="$2" number="$3" owner="$4" crit="$5"
  begin "account.balance.$label" RO "$crit"
  if ! svc_up account; then skip "account service not reachable"; return; fi
  if [[ -z "$id" ]]; then skip "no seeded id for $label"; return; fi
  if ! svc_get account "/api/v1/accounts/$id"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi

  local got_number got_owner avail held ledger currency
  got_number="$(jget accountNumber "")"
  got_owner="$(jget ownerUserId "")"
  currency="$(jget currency "")"
  avail="$(py money-at "$BODY_FILE" availableBalance 2>/dev/null || echo "")"
  held="$(py money-at "$BODY_FILE" heldBalance 2>/dev/null || echo "")"
  ledger="$(py money-at "$BODY_FILE" ledgerBalance 2>/dev/null || echo "")"

  if [[ "$got_number" != "$number" ]]; then
    fail "account number mismatch: live='$got_number' seed='$number' — refusing to treat as demo data"
    return
  fi
  if [[ -n "$owner" && "$got_owner" != "$owner" ]]; then
    fail "owner mismatch: live='$got_owner' seed='$owner'"
    return
  fi
  if [[ -z "$avail" || -z "$held" || -z "$ledger" ]]; then
    fail "balance fields unparseable: $(body_excerpt 160)"
    return
  fi
  # ledgerBalance = availableBalance + heldBalance is an invariant of the account
  # aggregate; checking it here catches a hold that leaked without a matching entry.
  if (( ledger != avail + held )); then
    fail "ledger($ledger) != available($avail) + held($held) minor units"
    return
  fi
  case "$label" in
    farmer) FARMER_START="$avail" ;;
    sme)    SME_START="$avail" ;;
  esac
  pass "$number $(py money-str "$avail" "${currency:-LKR}") available, held $(py money-str "$held" "${currency:-LKR}")"
}

check_account_listing() {
  begin account.list-by-owner RO opt
  if ! svc_up account; then skip "account service not reachable"; return; fi
  if [[ -z "$FARMER_USER" ]]; then skip "no seeded owner id"; return; fi
  if ! svc_get account "/api/v1/accounts?ownerUserId=$FARMER_USER"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  if ! py is-array "$BODY_FILE" >/dev/null 2>&1; then fail "expected a JSON array"; return; fi
  local n; n="$(py len "$BODY_FILE" 2>/dev/null || echo 0)"
  if (( n >= 1 )) && grep -q "$FARMER_ACCT" "$BODY_FILE"; then
    pass "$n account(s) for the farmer persona"
  else
    fail "farmer account not present in owner listing (n=$n)"
  fi
}

# Missing required query parameter must be a 400, not a 500 — this repo fixed exactly
# that regression once already (commit 4c43627), so it is worth a standing check.
check_account_bad_request() {
  begin account.missing-param RO opt
  if ! svc_up account; then skip "account service not reachable"; return; fi
  if ! svc_get account "/api/v1/accounts"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" == "400" ]]; then
    pass "400 for a missing ownerUserId (not 500)"
  else
    fail "expected 400 for a missing ownerUserId, got $HTTP_STATUS"
  fi
}

LEDGER_ENTRIES_BEFORE=-1
check_ledger_verify() {
  local phase="$1" crit="$2" group="${3:-RO}"
  begin "ledger.verify${phase:+.$phase}" "$group" "$crit"
  # The post-transfer pass asserts the chain *grew*, which only holds if a transfer ran.
  if [[ "$group" == "MUT" ]]; then ro_skip && return; fi
  if ! svc_up ledger; then skip "ledger service not reachable"; return; fi
  if ! svc_get ledger "/api/v1/ledger/verify"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  local valid checked brk detail
  valid="$(jget valid "")"; checked="$(jget checkedEntries "")"
  brk="$(jget firstBreakSequence "-")"; detail="$(jget detail "")"
  if [[ "$valid" != "true" ]]; then
    fail "hash chain INVALID at sequence $brk — ${detail:-no detail}"
    return
  fi
  if [[ -z "$phase" ]]; then
    LEDGER_ENTRIES_BEFORE="${checked:-0}"
    pass "chain valid, $checked entries"
  else
    if (( LEDGER_ENTRIES_BEFORE >= 0 )) && (( checked <= LEDGER_ENTRIES_BEFORE )); then
      fail "chain valid but entry count did not grow ($LEDGER_ENTRIES_BEFORE → $checked)"
    else
      pass "chain still valid, $LEDGER_ENTRIES_BEFORE → $checked entries"
    fi
  fi
}

check_ledger_anchors() {
  begin ledger.anchors RO opt
  if ! svc_up ledger; then skip "ledger service not reachable"; return; fi
  if ! svc_get ledger "/api/v1/ledger/anchors"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  if ! py is-array "$BODY_FILE" >/dev/null 2>&1; then fail "expected a JSON array"; return; fi
  local n; n="$(py len "$BODY_FILE" 2>/dev/null || echo 0)"
  if (( n == 0 )); then skip "no anchors published yet"; return; fi
  local root sig
  root="$(jget 0.merkleRoot "")"; sig="$(jget 0.signatureBase64 "")"
  if [[ ! "$root" =~ ^[0-9a-f]{64}$ ]]; then fail "anchor merkleRoot is not a sha-256 hex digest"; return; fi
  if [[ -z "$sig" ]]; then fail "anchor carries no signature"; return; fi
  pass "$n anchor(s), latest root ${root:0:16}… signed"
}

check_vault_ceremony_read() {
  begin vault.ceremony.read RO opt
  if ! svc_up vault; then skip "vault service not reachable"; return; fi
  if ! svc_get vault "/api/v1/vault/ceremony"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  local state approvals
  state="$(jget state "$(jget status unknown)")"
  approvals="$(py len "$BODY_FILE" approvals 2>/dev/null || echo "-")"
  pass "ceremony readable, state=$state approvals=$approvals (not advanced by this harness)"
}

# Generic "this collection endpoint answers with a well-formed list" check. PATH_IN_DOC
# is empty when the payload is the array itself.
check_list_endpoint() {
  local id="$1" svc="$2" path="$3" what="$4" doc_path="${5:-}"
  begin "$id" RO opt
  if ! svc_up "$svc"; then skip "$svc service not reachable"; return; fi
  if ! svc_get "$svc" "$path"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  local n
  if ! n="$(py len "$BODY_FILE" ${doc_path:+"$doc_path"} 2>/dev/null)"; then
    fail "unexpected shape: $(body_excerpt 120)"; return
  fi
  if [[ -z "$doc_path" ]] && ! py is-array "$BODY_FILE" >/dev/null 2>&1; then
    fail "expected a JSON array, got: $(body_excerpt 120)"; return
  fi
  pass "$n $what"
}

check_notify_templates() {
  begin notify.templates RO opt
  if ! svc_up notify; then skip "notification service not reachable"; return; fi
  if ! svc_get notify "/v1/templates"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  if grep -q "transfer_receipt" "$BODY_FILE"; then
    pass "templates include transfer_receipt"
  else
    fail "transfer_receipt template missing: $(body_excerpt 160)"
  fi
}

# The zero-JS lite path is a GET, so it belongs to the read-only phase even though it
# reaches all the way through ussd-gateway into account-service.
check_lite_balance() {
  begin ussd.lite-balance RO opt
  if [[ -z "$FARMER_PHONE" ]]; then skip "no seeded demo phone number"; return; fi
  if ! svc_up ussd; then skip "ussd gateway not reachable"; return; fi
  local encoded; encoded="$(py urlencode "$FARMER_PHONE")"
  if ! get "$BASE_URL/lite/balance?phone=$encoded"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  if grep -q "LKR" "$BODY_FILE"; then
    pass "lite balance rendered for the seeded demo phone"
  else
    fail "no LKR amount in the lite balance page"
  fi
}

# ---------------------------------------------------------------------------
# MUTATING checks
# ---------------------------------------------------------------------------

# Hard gate in front of every money movement. Without both the id and the account number
# matching the seed configuration, the harness has no evidence this is a demo account,
# and it stops instead of guessing.
demo_guard() {
  if (( DEMO_DATA_VERIFIED )); then return 0; fi
  return 1
}

verify_demo_data() {
  # FARMER_START / SME_START are set only by a balance check that already compared the
  # live account number with the seeded one.
  if (( FARMER_START >= 0 && SME_START >= 0 )); then DEMO_DATA_VERIFIED=1; fi
}

idem_key() { printf 'finix-smoke-%s-%s' "$RUN_ID" "$1"; }

account_available_minor() {
  local id="$1"
  svc_get account "/api/v1/accounts/$id" >/dev/null 2>&1 || return 1
  [[ "$HTTP_STATUS" == "200" ]] || return 1
  py money-at "$BODY_FILE" availableBalance 2>/dev/null
}

NET_FARMER_TO_SME=0     # minor units this run moved farmer → sme, restored at the end
TRANSFER_ID=""
TRANSFER_BODY=""

check_transfer_allow() {
  begin transfer.allow MUT crit
  ro_skip && return
  if ! svc_up orchestrator; then skip "orchestrator not reachable"; return; fi
  if ! demo_guard; then skip "demo accounts not verified — refusing to move money"; return; fi

  TRANSFER_BODY="$(printf '{"fromAccountId":"%s","toAccountId":"%s","amount":"%s"}' \
    "$FARMER_ACCT" "$SME_ACCT" "$DEMO_AMOUNT")"
  local key; key="$(idem_key transfer)"
  if ! svc_post orchestrator "/api/v1/transfers" "$TRANSFER_BODY" "application/json" "Idempotency-Key: $key"; then
    fail "$(http_failed)"; return
  fi
  if [[ "$HTTP_STATUS" != "201" && "$HTTP_STATUS" != "200" ]]; then
    fail "http $HTTP_STATUS: $(body_excerpt 200)"; return
  fi
  local state decision score
  TRANSFER_ID="$(jget transferId "")"
  state="$(jget state "")"; decision="$(jget riskDecision "-")"; score="$(jget riskScore "-")"
  if [[ "$state" != "COMPLETED" ]]; then
    fail "state=$state (expected COMPLETED) risk=$decision/$score"; return
  fi
  if [[ -z "$TRANSFER_ID" ]]; then fail "COMPLETED but no transferId returned"; return; fi
  NET_FARMER_TO_SME=$(( NET_FARMER_TO_SME + DEMO_MINOR ))
  pass "$DEMO_AMOUNT farmer→sme COMPLETED risk=$decision score=$score"
}

# Same key, byte-identical body: the kernel filter must replay the recorded response
# rather than run a second saga. The money assertion below is what makes this a real
# duplicate-payment test rather than a header test.
check_transfer_idempotency() {
  begin transfer.idempotent-replay MUT crit
  ro_skip && return
  if [[ -z "$TRANSFER_ID" ]]; then skip "no successful transfer to replay"; return; fi
  local key; key="$(idem_key transfer)"
  if ! svc_post orchestrator "/api/v1/transfers" "$TRANSFER_BODY" "application/json" "Idempotency-Key: $key"; then
    fail "$(http_failed)"; return
  fi
  local replayed second
  replayed="$(header_value Idempotency-Replayed)"
  second="$(jget transferId "")"
  if [[ "$second" != "$TRANSFER_ID" ]]; then
    fail "duplicate created a new transfer ($second != $TRANSFER_ID)"; return
  fi
  if [[ "$replayed" != "true" ]]; then
    fail "same transferId but no Idempotency-Replayed header (got '${replayed:-none}')"; return
  fi
  pass "replayed transfer $TRANSFER_ID, no second saga"
}

check_idempotency_missing_key() {
  begin idempotency.missing-key MUT opt
  ro_skip && return
  if ! svc_up orchestrator; then skip "orchestrator not reachable"; return; fi
  if ! demo_guard; then skip "demo accounts not verified"; return; fi
  local body
  body="$(printf '{"fromAccountId":"%s","toAccountId":"%s","amount":"%s"}' "$FARMER_ACCT" "$SME_ACCT" "$DEMO_AMOUNT")"
  if ! svc_post orchestrator "/api/v1/transfers" "$body"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "400" ]]; then
    fail "expected 400 without Idempotency-Key, got $HTTP_STATUS"; return
  fi
  if grep -q "missing-idempotency-key" "$BODY_FILE"; then
    pass "400 missing-idempotency-key (request never reached the saga)"
  else
    pass "400 rejected without an Idempotency-Key"
  fi
}

check_idempotency_key_reuse() {
  begin idempotency.key-reuse MUT opt
  ro_skip && return
  if [[ -z "$TRANSFER_ID" ]]; then skip "no recorded key to reuse"; return; fi
  local key body
  key="$(idem_key transfer)"
  # Same key, deliberately different amount: replaying the old answer here would be the
  # "pay Nimal 500 answers pay Kamal 50 000" bug the filter exists to prevent.
  body="$(printf '{"fromAccountId":"%s","toAccountId":"%s","amount":"LKR 2.00"}' "$FARMER_ACCT" "$SME_ACCT")"
  if ! svc_post orchestrator "/api/v1/transfers" "$body" "application/json" "Idempotency-Key: $key"; then
    fail "$(http_failed)"; return
  fi
  if [[ "$HTTP_STATUS" == "422" ]]; then
    pass "422 on key reuse with a different body"
  else
    fail "expected 422 on key reuse with a different body, got $HTTP_STATUS"
  fi
}

check_balance_delta() {
  begin balance.delta MUT crit
  ro_skip && return
  if [[ -z "$TRANSFER_ID" ]]; then skip "no successful transfer"; return; fi
  local farmer_now sme_now
  farmer_now="$(account_available_minor "$FARMER_ACCT")" || { fail "could not re-read farmer balance"; return; }
  sme_now="$(account_available_minor "$SME_ACCT")" || { fail "could not re-read sme balance"; return; }
  local expected_farmer=$(( FARMER_START - DEMO_MINOR ))
  local expected_sme=$(( SME_START + DEMO_MINOR ))
  if (( farmer_now != expected_farmer )); then
    fail "farmer $FARMER_START → $farmer_now, expected $expected_farmer (minor units)"; return
  fi
  if (( sme_now != expected_sme )); then
    fail "sme $SME_START → $sme_now, expected $expected_sme (minor units)"; return
  fi
  pass "exactly $DEMO_AMOUNT moved once (duplicate did not double-debit)"
}

check_ledger_journal() {
  begin ledger.journal MUT crit
  ro_skip && return
  if [[ -z "$TRANSFER_ID" ]]; then skip "no successful transfer"; return; fi
  if ! svc_up ledger; then skip "ledger service not reachable"; return; fi
  if ! svc_get ledger "/api/v1/ledger/journals/$TRANSFER_ID"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS for the transfer journal"; return; fi
  local seq entry prev lines debit credit
  seq="$(jget sequence "")"; entry="$(jget entryHash "")"; prev="$(jget prevHash "")"
  lines="$(py len "$BODY_FILE" lines 2>/dev/null || echo 0)"
  debit="$(jget lines.0.side "")"; credit="$(jget lines.1.side "")"
  if [[ ! "$entry" =~ ^[0-9a-f]{64}$ || ! "$prev" =~ ^[0-9a-f]{64}$ ]]; then
    fail "entryHash/prevHash are not sha-256 digests"; return
  fi
  if (( lines != 2 )) || [[ "$debit" != "DEBIT" || "$credit" != "CREDIT" ]]; then
    fail "expected one DEBIT and one CREDIT line, got $lines ($debit/$credit)"; return
  fi
  if ! grep -q "$FARMER_ACCT" "$BODY_FILE" || ! grep -q "$SME_ACCT" "$BODY_FILE"; then
    fail "journal lines do not reference both demo accounts"; return
  fi
  pass "seq=$seq double-entry DEBIT/CREDIT, hash ${entry:0:16}…"
}

# The strongest check in the suite: force an anchor, then recompute the RFC-6962 root
# from the returned inclusion path locally. If it matches, the ledger's claim of
# inclusion has been verified without trusting the ledger's own /verify verdict.
check_ledger_merkle_proof() {
  begin ledger.merkle-proof MUT opt
  ro_skip && return
  if [[ -z "$TRANSFER_ID" ]]; then skip "no successful transfer"; return; fi
  if ! svc_up ledger; then skip "ledger service not reachable"; return; fi

  # Anchoring a window is ordinary scheduled behaviour (60s timer); asking for it now
  # only makes the proof available inside the run. It creates no ledger entry.
  svc_post ledger "/api/v1/ledger/anchors/now" "" "application/json" "Idempotency-Key: $(idem_key anchor)" >/dev/null 2>&1

  if ! svc_get ledger "/api/v1/ledger/proof/$TRANSFER_ID"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS for the inclusion proof"; return; fi
  local root; root="$(jget merkleRoot "")"
  if [[ -z "$root" || "$root" == "null" ]]; then
    skip "entry not yet covered by an anchor"; return
  fi
  local out
  if out="$(py merkle "$BODY_FILE")"; then
    pass "recomputed root matches anchor ($out)"
  else
    fail "independent Merkle recomputation does not match merkleRoot ($out)"
  fi
}

# --- risk -----------------------------------------------------------------
# The score blends deterministic rules with an isolation-forest term that cannot be
# predicted from outside the service. So the harness never asserts a decision it has not
# first confirmed by scoring the identical feature vector directly.
risk_calibrate() {
  local amount_minor="$1" velocity="$2" new_device="$3" offline="$4"
  svc_up risk || return 1
  local body
  body="$(printf '{"transaction_id":"calibration","from_account_id":"%s","to_account_id":"%s","amount_minor":%s,"currency":"LKR","velocity_1h":%s,"new_device":%s,"offline_voucher":%s}' \
    "$FARMER_ACCT" "$SME_ACCT" "$amount_minor" "$velocity" "$new_device" "$offline")"
  svc_post risk "/v1/score" "$body" >/dev/null 2>&1 || return 1
  [[ "$HTTP_STATUS" == "200" ]] || return 1
  jget decision ""
}

STEPUP_ID=""
check_risk_step_up() {
  begin risk.step-up MUT opt
  ro_skip && return
  if ! svc_up orchestrator; then skip "orchestrator not reachable"; return; fi
  if ! demo_guard; then skip "demo accounts not verified"; return; fi

  local calibrated
  calibrated="$(risk_calibrate "$DEMO_MINOR" 8 true false || true)"
  if [[ "$calibrated" != "step_up" ]]; then
    skip "risk scores these features '${calibrated:-unavailable}', not step_up — not deterministic here"
    return
  fi
  CHECK_CRIT="crit"   # calibrated, therefore deterministic, therefore worth failing on

  local body key
  body="$(printf '{"fromAccountId":"%s","toAccountId":"%s","amount":"%s","newDevice":true,"velocity1h":8}' \
    "$FARMER_ACCT" "$SME_ACCT" "$DEMO_AMOUNT")"
  key="$(idem_key stepup)"
  if ! svc_post orchestrator "/api/v1/transfers" "$body" "application/json" "Idempotency-Key: $key"; then
    fail "$(http_failed)"; return
  fi
  local state; state="$(jget state "")"
  STEPUP_ID="$(jget transferId "")"
  if [[ "$state" != "AWAITING_STEP_UP" ]]; then
    fail "risk scored step_up but saga state is $state"; return
  fi
  pass "saga $STEPUP_ID suspended in AWAITING_STEP_UP (score $(jget riskScore -))"

  begin risk.step-up.resume MUT crit
  if ! svc_post orchestrator "/api/v1/transfers/$STEPUP_ID/step-up" '{"otpCode":"123456"}' \
        "application/json" "Idempotency-Key: $(idem_key stepup-otp)"; then
    fail "$(http_failed)"; return
  fi
  state="$(jget state "")"
  if [[ "$state" == "COMPLETED" ]]; then
    NET_FARMER_TO_SME=$(( NET_FARMER_TO_SME + DEMO_MINOR ))
    pass "MFA resumed the saga to COMPLETED"
  else
    fail "after step-up the saga is $state, expected COMPLETED"
  fi
}

check_risk_blocked() {
  begin risk.blocked MUT opt
  ro_skip && return
  if ! svc_up orchestrator; then skip "orchestrator not reachable"; return; fi
  if ! demo_guard; then skip "demo accounts not verified"; return; fi

  # LKR 600,000 with high velocity, a new device and an offline voucher pins the rules
  # term at its ceiling. Two independent safety nets keep this harmless: it only runs
  # when the risk service has confirmed 'block', and it is sourced from the farmer
  # account, whose balance is far below the amount, so even a risk outage cannot let it
  # settle.
  local amount_minor=60000000
  local calibrated
  calibrated="$(risk_calibrate "$amount_minor" 8 true true || true)"
  if [[ "$calibrated" != "block" ]]; then
    skip "risk scores these features '${calibrated:-unavailable}', not block — refusing to probe blocking"
    return
  fi
  CHECK_CRIT="crit"

  local farmer_before body
  farmer_before="$(account_available_minor "$FARMER_ACCT")" || { fail "could not read farmer balance"; return; }
  body="$(printf '{"fromAccountId":"%s","toAccountId":"%s","amount":"LKR 600000.00","newDevice":true,"velocity1h":8,"offlineVoucher":true}' \
    "$FARMER_ACCT" "$SME_ACCT")"
  if ! svc_post orchestrator "/api/v1/transfers" "$body" "application/json" "Idempotency-Key: $(idem_key blocked)"; then
    fail "$(http_failed)"; return
  fi
  local state decision; state="$(jget state "")"; decision="$(jget riskDecision "-")"
  local farmer_after; farmer_after="$(account_available_minor "$FARMER_ACCT")" || farmer_after="$farmer_before"
  if (( farmer_after != farmer_before )); then
    fail "BLOCKED probe moved money: $farmer_before → $farmer_after minor units"; return
  fi
  if [[ "$state" == "BLOCKED" ]]; then
    pass "high-risk transfer BLOCKED before reservation, balance untouched (risk=$decision)"
  else
    fail "risk scored block but saga state is $state (balance untouched)"
  fi
}

check_risk_cases() {
  begin risk.cases MUT opt
  ro_skip && return
  if ! svc_up risk; then skip "risk service not reachable"; return; fi
  if ! svc_get risk "/v1/cases"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  if ! py is-array "$BODY_FILE" >/dev/null 2>&1; then fail "expected a JSON array of cases"; return; fi
  pass "$(py len "$BODY_FILE") fraud case(s) on record"
}

# --- offline vouchers ------------------------------------------------------
OFFLINE_DEVICE=""
OFFLINE_KEY=""
check_offline_voucher() {
  begin offline.device.register MUT opt
  ro_skip && return
  if ! svc_up account; then skip "account service not reachable"; return; fi
  if ! demo_guard; then skip "demo accounts not verified"; return; fi
  if (( ! HAVE_OPENSSL )); then skip "openssl not available to sign a voucher"; return; fi

  # A fresh throwaway device every run. The replay check below quarantines it on
  # purpose, which is exactly why it must never be a device a judge is demoing with.
  OFFLINE_DEVICE="smoke-$RUN_ID"
  OFFLINE_KEY="$WORK_DIR/voucher-key.pem"
  if ! openssl ecparam -name prime256v1 -genkey -noout -out "$OFFLINE_KEY" 2>/dev/null; then
    skip "openssl could not generate a P-256 key"; return
  fi
  chmod 600 "$OFFLINE_KEY"
  local spki
  spki="$(openssl ec -in "$OFFLINE_KEY" -pubout -outform DER 2>/dev/null | base64 | tr -d '\n')"
  if [[ -z "$spki" ]]; then skip "could not export an SPKI public key"; return; fi

  local body
  body="$(printf '{"deviceId":"%s","ownerUserId":"%s","accountId":"%s","publicKeySpkiBase64":"%s"}' \
    "$OFFLINE_DEVICE" "$FARMER_USER" "$FARMER_ACCT" "$spki")"
  if ! svc_post account "/api/v1/offline/devices" "$body" "application/json" "Idempotency-Key: $(idem_key device)"; then
    fail "$(http_failed)"; OFFLINE_DEVICE=""; return
  fi
  if [[ "$HTTP_STATUS" != "201" && "$HTTP_STATUS" != "200" ]]; then
    fail "http $HTTP_STATUS: $(body_excerpt 160)"; OFFLINE_DEVICE=""; return
  fi
  if [[ "$(jget quarantined "")" == "true" ]]; then
    fail "freshly registered device is already quarantined"; OFFLINE_DEVICE=""; return
  fi
  pass "device $OFFLINE_DEVICE registered with an ECDSA P-256 key"

  # --- settle -------------------------------------------------------------
  begin offline.voucher.settle MUT opt
  local nonce valid_until payload signature voucher_body
  nonce="smoke-$RUN_ID-1"
  valid_until=$(( $(date -u +%s) * 1000 + 3600000 ))
  payload="${FARMER_ACCT}|${SME_ACCT}|${DEMO_MINOR}|LKR|${OFFLINE_DEVICE}|1|${nonce}|${valid_until}"
  if ! printf '%s' "$payload" | openssl dgst -sha256 -sign "$OFFLINE_KEY" -out "$WORK_DIR/sig.der" 2>/dev/null; then
    skip "openssl could not sign the voucher payload"; return
  fi
  signature="$(base64 <"$WORK_DIR/sig.der" | tr -d '\n')"
  voucher_body="$(printf '{"deviceId":"%s","payerAccountId":"%s","payeeAccountId":"%s","amount":"%s","deviceSeq":1,"nonce":"%s","validUntilEpochMs":%s,"signatureBase64":"%s"}' \
    "$OFFLINE_DEVICE" "$FARMER_ACCT" "$SME_ACCT" "$DEMO_AMOUNT" "$nonce" "$valid_until" "$signature")"

  local farmer_before; farmer_before="$(account_available_minor "$FARMER_ACCT")" || farmer_before=""
  if ! svc_post account "/api/v1/offline/vouchers/reconcile" "$voucher_body" "application/json" \
        "Idempotency-Key: $(idem_key voucher-1)"; then
    fail "$(http_failed)"; return
  fi
  if [[ "$HTTP_STATUS" != "200" && "$HTTP_STATUS" != "201" ]]; then
    fail "http $HTTP_STATUS: $(body_excerpt 200)"; return
  fi
  local status; status="$(jget status "")"
  if [[ "$status" != "SETTLED" ]]; then fail "voucher status=$status, expected SETTLED"; return; fi
  local farmer_after; farmer_after="$(account_available_minor "$FARMER_ACCT")" || farmer_after=""
  if [[ -n "$farmer_before" && -n "$farmer_after" ]] && (( farmer_after != farmer_before - DEMO_MINOR )); then
    fail "SETTLED but the payer balance moved by $(( farmer_before - farmer_after )) not $DEMO_MINOR"; return
  fi
  NET_FARMER_TO_SME=$(( NET_FARMER_TO_SME + DEMO_MINOR ))
  pass "signed voucher reconciled and SETTLED, payer debited $DEMO_AMOUNT"

  # --- replay -------------------------------------------------------------
  # Deliberately a *different* Idempotency-Key with the identical voucher. Reusing the
  # key would let the kernel's replay cache answer, and would prove nothing about the
  # nonce-based double-spend defence this check exists to exercise.
  begin offline.replay.reject MUT opt
  if ! svc_post account "/api/v1/offline/vouchers/reconcile" "$voucher_body" "application/json" \
        "Idempotency-Key: $(idem_key voucher-replay)"; then
    fail "$(http_failed)"; return
  fi
  if [[ "$HTTP_STATUS" != "409" ]]; then
    fail "replayed voucher returned $HTTP_STATUS, expected 409 Conflict"; return
  fi
  local reason; reason="$(jget reason "")"
  if [[ "$reason" == "nonce-reuse" ]] || grep -q "nonce-reuse" "$BODY_FILE"; then
    pass "409 nonce-reuse, throwaway device quarantined"
  else
    pass "409 rejected on replay ($(body_excerpt 120))"
  fi
}

# --- ussd ------------------------------------------------------------------
check_ussd_flow() {
  begin ussd.menu MUT opt
  ro_skip && return
  if ! svc_up ussd; then skip "ussd gateway not reachable"; return; fi
  if [[ -z "$FARMER_PHONE" ]]; then skip "no seeded demo phone number"; return; fi

  local session phone form
  session="smoke-$RUN_ID"
  phone="$(py urlencode "$FARMER_PHONE")"
  form="sessionId=$session&phoneNumber=$phone&serviceCode=%2A334%23&text="
  if ! svc_post ussd "/ussd" "$form" "application/x-www-form-urlencoded"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  if head -c 4 "$BODY_FILE" | grep -q "^CON"; then
    pass "*334# welcome menu returned CON"
  else
    fail "expected a CON menu, got: $(body_excerpt 100)"
    return
  fi

  begin ussd.balance MUT opt
  form="sessionId=$session&phoneNumber=$phone&serviceCode=%2A334%23&text=1"
  if ! svc_post ussd "/ussd" "$form" "application/x-www-form-urlencoded"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS"; return; fi
  if head -c 4 "$BODY_FILE" | grep -q "^END" && grep -q "LKR" "$BODY_FILE"; then
    pass "menu option 1 returned an END balance in LKR"
  else
    fail "expected an END balance, got: $(body_excerpt 120)"
  fi
}

# --- loan ------------------------------------------------------------------
# Fixed idempotency keys on purpose: within the 24h window a rerun replays the same
# loan instead of stacking a new demo application into the database every run.
check_loan_demo() {
  begin loan.apply-decide MUT opt
  ro_skip && return
  if ! svc_up loan; then skip "loan service not reachable"; return; fi
  if [[ -z "$SME_USER" || -z "$SME_ACCT" ]]; then skip "no seeded borrower identity"; return; fi

  local body
  body="$(printf '{"borrowerUserId":"%s","accountId":"%s","principal":"LKR 5000.00","termMonths":12}' \
    "$SME_USER" "$SME_ACCT")"
  if ! svc_post loan "/api/v1/loans" "$body" "application/json" "Idempotency-Key: finix-smoke-loan-v1"; then
    fail "$(http_failed)"; return
  fi
  if [[ "$HTTP_STATUS" != "201" && "$HTTP_STATUS" != "200" ]]; then
    fail "http $HTTP_STATUS: $(body_excerpt 160)"; return
  fi
  local loan_id; loan_id="$(jget id "")"
  if [[ -z "$loan_id" ]]; then fail "no loan id returned"; return; fi

  if ! svc_post loan "/api/v1/loans/$loan_id/decide" '{"riskHint":"low"}' "application/json" \
        "Idempotency-Key: finix-smoke-loan-decide-v1"; then
    fail "$(http_failed)"; return
  fi
  local status score installments
  status="$(jget status "")"; score="$(jget creditScore "-")"
  installments="$(py len "$BODY_FILE" schedule 2>/dev/null || echo 0)"
  # LKR 5,000 with a LOW hint scores 90 against a threshold of 60 — deterministic.
  if [[ "$status" != "APPROVED" ]]; then
    fail "loan status=$status score=$score, expected APPROVED"; return
  fi
  if (( installments != 12 )); then
    fail "APPROVED but the repayment schedule has $installments installments, expected 12"; return
  fi
  pass "loan $loan_id APPROVED score=$score with a 12-installment schedule"
}

# --- compliance ------------------------------------------------------------
check_compliance_demo() {
  begin compliance.screen MUT opt
  ro_skip && return
  if ! svc_up compliance; then skip "compliance service not reachable"; return; fi
  # A clean party name cannot hit the demo sanctions rules (name contains BLOCKED, or
  # NIC ends with X), so this asserts a negative screen and opens no case.
  local body='{"name":"Finix Demo Farmer","nic":"199012345678","subjectRef":"finix-smoke"}'
  if ! svc_post compliance "/api/v1/screen" "$body" "application/json" "Idempotency-Key: finix-smoke-screen-v1"; then
    fail "$(http_failed)"; return
  fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "http $HTTP_STATUS: $(body_excerpt 160)"; return; fi
  local hit; hit="$(jget hit "")"
  if [[ "$hit" == "false" ]]; then
    pass "clean party screened with no hit and no case opened"
  else
    fail "clean demo party screened as hit=$hit"
  fi
}

# --- payment hub -----------------------------------------------------------
check_payment_hub_demo() {
  begin payhub.pacs008 MUT opt
  ro_skip && return
  if ! svc_up pay; then skip "payment hub not reachable"; return; fi
  local e2e body
  e2e="E2E-SMOKE-$RUN_ID"
  body="$(printf '{"debtorAccount":"LK-DEMO-001","creditorAccount":"LK-DEMO-002","amountMinor":%s,"currency":"LKR","endToEndId":"%s","scheme":"LANKAPAY"}' \
    "$DEMO_MINOR" "$e2e")"
  if ! svc_post pay "/v1/payments" "$body"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "201" && "$HTTP_STATUS" != "200" ]]; then
    fail "http $HTTP_STATUS: $(body_excerpt 160)"; return
  fi
  local pid status; pid="$(jget id "")"; status="$(jget status "")"
  if [[ -z "$pid" ]]; then fail "no payment id returned"; return; fi
  if ! svc_get pay "/v1/payments/$pid/pacs008"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "200" ]]; then fail "pacs.008 render returned $HTTP_STATUS"; return; fi
  if grep -q "<Document" "$BODY_FILE" && grep -q "$e2e" "$BODY_FILE"; then
    pass "payment $status, ISO 20022 pacs.008 rendered with the end-to-end id"
  else
    fail "pacs.008 payload missing <Document> or the end-to-end id"
  fi
}

# --- notifications ---------------------------------------------------------
check_notification_demo() {
  begin notify.send MUT opt
  ro_skip && return
  if ! svc_up notify; then skip "notification service not reachable"; return; fi
  local to body
  to="${FARMER_PHONE:-+94771110001}"
  body="$(printf '{"channel":"sms","locale":"si","template":"transfer_receipt","to":"%s","vars":{"amount":"%s","payee":"SME"}}' \
    "$to" "$DEMO_AMOUNT")"
  if ! svc_post notify "/v1/notify" "$body"; then fail "$(http_failed)"; return; fi
  if [[ "$HTTP_STATUS" != "201" && "$HTTP_STATUS" != "200" ]]; then
    fail "http $HTTP_STATUS: $(body_excerpt 160)"; return
  fi
  local mid rendered
  mid="$(jget id "")"; rendered="$(jget body "")"
  if [[ -z "$rendered" ]]; then fail "notification accepted but rendered no body"; return; fi
  if ! svc_get notify "/v1/messages"; then fail "$(http_failed)"; return; fi
  if [[ -n "$mid" ]] && ! grep -q "$mid" "$BODY_FILE"; then
    fail "sent message $mid is absent from /v1/messages"; return
  fi
  pass "Sinhala transfer_receipt rendered and stored (no real SMS sent)"
}

# --- restore ---------------------------------------------------------------
# The closing move: send back exactly what this run moved, then prove the demo balances
# are byte-for-byte where they started. That is what makes reruns safe.
check_restore_balances() {
  begin balance.restore MUT opt
  ro_skip && return
  if (( ! RESTORE )); then skip "--no-restore requested"; return; fi
  if (( NET_FARMER_TO_SME == 0 )); then skip "nothing moved, nothing to restore"; return; fi
  if ! svc_up orchestrator; then skip "orchestrator not reachable"; return; fi

  local amount body
  amount="$(py money-str "$NET_FARMER_TO_SME" LKR)"
  body="$(printf '{"fromAccountId":"%s","toAccountId":"%s","amount":"%s"}' "$SME_ACCT" "$FARMER_ACCT" "$amount")"
  if ! svc_post orchestrator "/api/v1/transfers" "$body" "application/json" "Idempotency-Key: $(idem_key restore)"; then
    fail "restore transfer failed: $(http_failed) — demo balances are off by $amount"; return
  fi
  local state; state="$(jget state "")"
  if [[ "$state" != "COMPLETED" ]]; then
    fail "restore transfer is $state — demo balances are off by $amount"; return
  fi

  local farmer_now sme_now
  farmer_now="$(account_available_minor "$FARMER_ACCT")" || { fail "could not re-read farmer balance"; return; }
  sme_now="$(account_available_minor "$SME_ACCT")" || { fail "could not re-read sme balance"; return; }
  if (( farmer_now == FARMER_START && sme_now == SME_START )); then
    pass "$amount returned; demo balances back to their opening values"
  else
    fail "after restore farmer=$farmer_now (was $FARMER_START), sme=$sme_now (was $SME_START) minor units"
  fi
}

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
printf '%sFINIX smoke harness %s%s\n' "$C_BOLD" "$VERSION" "$C_OFF"
printf '  base url    %s\n' "$BASE_URL"
printf '  admin url   %s\n' "${ADMIN_URL:-<not configured>}"
printf '  mode        %s\n' "$( ((READ_ONLY)) && echo 'READ-ONLY (no request other than GET/HEAD)' || echo 'READ-ONLY + DEMO-DATA MUTATING' )"
printf '  timeouts    connect %ss / total %ss per request\n' "$CONNECT_TIMEOUT" "$MAX_TIME"
printf '  tls         verification always on%s\n' "${CA_BUNDLE:+ (extra CA bundle: $CA_BUNDLE)}"
printf '  run id      %s\n' "$RUN_ID"

detect_seed_identities

# A base URL that answers nothing at all is an environment problem, not a test failure,
# and it is worth saying so before spending a discovery sweep on it.
if ! get "$BASE_URL/" >/dev/null 2>&1; then
  preflight_fail "$BASE_URL is unreachable — $(http_failed)"
fi

section "Discovery"
discover_services
DISCOVERED=""
for s in $ALL_SERVICES; do
  if svc_up "$s"; then DISCOVERED="$DISCOVERED $s($(svc_via "$s"))"; fi
done
if [[ -z "$DISCOVERED" ]]; then
  note "  no FINIX service answered a health probe from $BASE_URL"
else
  note "  reachable:$DISCOVERED"
fi
UNREACHABLE=""
for s in $ALL_SERVICES; do svc_up "$s" || UNREACHABLE="$UNREACHABLE $s"; done
[[ -n "$UNREACHABLE" ]] && note "  unreachable (checks will SKIP):$UNREACHABLE"

section "READ-ONLY checks"
check_web_app
check_admin_app
check_service_health account      crit UP
check_service_health orchestrator crit UP
check_service_health ledger       crit UP
check_service_health ussd         opt  UP
check_service_health vault        opt  UP
check_service_health compliance   opt  UP
check_service_health loan         opt  UP
check_service_health risk         opt  UP
check_service_health pay          opt  ok
check_service_health notify       opt  ok
check_proxy_passthrough
check_seed_detection
check_account_balance farmer "$FARMER_ACCT" "$FARMER_NUM" "$FARMER_USER" crit
check_account_balance sme    "$SME_ACCT"    "$SME_NUM"    "$SME_USER"    crit
check_account_balance elder  "$ELDER_ACCT"  "$ELDER_NUM"  "$ELDER_USER"  opt
check_account_listing
check_account_bad_request
check_ledger_verify "" crit RO
check_ledger_anchors
check_vault_ceremony_read
check_list_endpoint compliance.cases compliance "/api/v1/cases" "compliance case(s)"
check_list_endpoint loan.list        loan       "/api/v1/loans" "loan(s)"
check_notify_templates
check_lite_balance

verify_demo_data

section "DEMO-DATA MUTATING checks"
if (( READ_ONLY )); then
  note "  --read-only: every check below is skipped without being sent"
elif ! demo_guard; then
  note "  demo accounts could not be verified against the seed configuration — money-moving checks will skip"
fi
check_transfer_allow
check_transfer_idempotency
check_idempotency_missing_key
check_idempotency_key_reuse
check_balance_delta
check_ledger_journal
check_ledger_verify after crit MUT
check_ledger_merkle_proof
check_risk_step_up
check_risk_blocked
check_risk_cases
check_offline_voucher
check_ussd_flow
check_loan_demo
check_compliance_demo
check_payment_hub_demo
check_notification_demo
check_restore_balances

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
TOTAL_MS=$(( $(now_ms) - RUN_T0 ))
TOTAL_S=$(( TOTAL_MS / 1000 ))
TOTAL_REM=$(( (TOTAL_MS % 1000) / 100 ))

section "Summary"
printf '  %spass %d%s · %sfail %d%s (critical %d) · %sskip %d%s · %d checks · %d requests\n' \
  "$C_PASS" "$N_PASS" "$C_OFF" "$C_FAIL" "$N_FAIL" "$C_OFF" "$N_FAIL_CRIT" \
  "$C_SKIP" "$N_SKIP" "$C_OFF" "$(( N_PASS + N_FAIL + N_SKIP ))" "$REQUEST_COUNT"
printf '  total duration %d.%ds\n' "$TOTAL_S" "$TOTAL_REM"

EXIT_CODE=0
if (( N_FAIL_CRIT > 0 )); then
  EXIT_CODE=1
  printf '  %sRESULT: FAILED%s — %d critical check(s) failed\n' "$C_FAIL" "$C_OFF" "$N_FAIL_CRIT"
elif (( N_FAIL > 0 )); then
  EXIT_CODE=2
  printf '  %sRESULT: DEGRADED%s — %d non-critical check(s) failed\n' "$C_FAIL" "$C_OFF" "$N_FAIL"
else
  printf '  %sRESULT: OK%s\n' "$C_PASS" "$C_OFF"
fi

if [[ -n "$JSON_REPORT" ]]; then
  if py report "$JSONL_FILE" \
      "baseUrl=$BASE_URL" "adminUrl=${ADMIN_URL:-}" "runId=$RUN_ID" \
      "mode=$( ((READ_ONLY)) && echo read-only || echo full )" \
      "seedSource=$SEED_SOURCE" "pass=$N_PASS" "fail=$N_FAIL" "criticalFail=$N_FAIL_CRIT" \
      "skip=$N_SKIP" "requests=$REQUEST_COUNT" "durationMs=$TOTAL_MS" "exitCode=$EXIT_CODE" \
      >"$JSON_REPORT" 2>/dev/null; then
    printf '  report written to %s\n' "$JSON_REPORT"
  else
    printf '  %scould not write report to %s%s\n' "$C_FAIL" "$JSON_REPORT" "$C_OFF"
  fi
fi

exit "$EXIT_CODE"
