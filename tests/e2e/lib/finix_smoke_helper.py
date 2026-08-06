#!/usr/bin/env python3
"""Parsing and crypto helpers for tests/e2e/finix-smoke.sh.

The harness itself is bash so a judge can read it top to bottom, but three jobs are
genuinely unpleasant in shell and are done here instead:

  * reading a value out of a JSON response without adding a `jq` dependency,
  * exact money arithmetic on the ``"LKR 1250.00"`` wire form (integer minor units,
    never floats),
  * recomputing an RFC-6962 Merkle root from an inclusion proof, which is the one
    check that verifies the ledger *without trusting the ledger's own verdict*.

Every subcommand prints a single line to stdout and uses the exit status to signal
"absent / mismatch" so the caller can branch with plain `if`.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from decimal import Decimal, InvalidOperation

EXIT_OK = 0
EXIT_MISSING = 1
EXIT_USAGE = 2

MONEY_RE = re.compile(r"^\s*([A-Z]{3})\s+(-?\d+(?:\.\d+)?)\s*$")

# Values under these JSON keys never reach the terminal or the report file. The list is
# deliberately broader than what this harness actually sends: a future check that starts
# passing a token should be redacted by default rather than by remembering to add it.
SECRET_KEYS = {
    "access_token",
    "accesstoken",
    "anchorpublickeybase64",
    "anchorsignaturebase64",
    "apikey",
    "api_key",
    "authorization",
    "client_secret",
    "clientsecret",
    "id_token",
    "otp",
    "otpcode",
    "password",
    "privatekey",
    "private_key",
    "publickeybase64",
    "publickeyspkibase64",
    "refresh_token",
    "secret",
    "signature",
    "signaturebase64",
    "token",
}

REDACTED = "***REDACTED***"


def _load(path: str):
    with open(path, "rb") as fh:
        raw = fh.read()
    if not raw.strip():
        raise ValueError("empty body")
    return json.loads(raw.decode("utf-8", "replace"))


def _walk(doc, path: str):
    """Resolve a dotted path. Integer segments index into lists: ``lines.0.side``."""
    if path in ("", "."):
        return doc
    current = doc
    for segment in path.split("."):
        if isinstance(current, list):
            if not segment.lstrip("-").isdigit():
                raise KeyError(segment)
            index = int(segment)
            if index >= len(current) or index < -len(current):
                raise KeyError(segment)
            current = current[index]
        elif isinstance(current, dict):
            if segment not in current:
                raise KeyError(segment)
            current = current[segment]
        else:
            raise KeyError(segment)
    return current


def _render(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (dict, list)):
        return json.dumps(value, separators=(",", ":"), sort_keys=True)
    return str(value)


def cmd_get(argv) -> int:
    """get FILE PATH [DEFAULT] — print one JSON value."""
    if len(argv) < 2:
        return EXIT_USAGE
    path_default = argv[2] if len(argv) > 2 else None
    try:
        value = _walk(_load(argv[0]), argv[1])
    except (OSError, ValueError, KeyError, TypeError):
        if path_default is not None:
            print(path_default)
            return EXIT_OK
        return EXIT_MISSING
    print(_render(value))
    return EXIT_OK


def cmd_has(argv) -> int:
    """has FILE PATH — exit 0 when the path resolves to a non-null value."""
    if len(argv) < 2:
        return EXIT_USAGE
    try:
        value = _walk(_load(argv[0]), argv[1])
    except (OSError, ValueError, KeyError, TypeError):
        return EXIT_MISSING
    return EXIT_OK if value is not None else EXIT_MISSING


def cmd_len(argv) -> int:
    """len FILE [PATH] — print the length of an array or object."""
    if not argv:
        return EXIT_USAGE
    try:
        value = _walk(_load(argv[0]), argv[1] if len(argv) > 1 else ".")
    except (OSError, ValueError, KeyError, TypeError):
        return EXIT_MISSING
    if not isinstance(value, (list, dict, str)):
        return EXIT_MISSING
    print(len(value))
    return EXIT_OK


def cmd_is_array(argv) -> int:
    if not argv:
        return EXIT_USAGE
    try:
        value = _walk(_load(argv[0]), argv[1] if len(argv) > 1 else ".")
    except (OSError, ValueError, KeyError, TypeError):
        return EXIT_MISSING
    return EXIT_OK if isinstance(value, list) else EXIT_MISSING


def _money_minor(text: str) -> int:
    """``"LKR 1250.00"`` -> 125000. Exact: Decimal in, integer out, no float ever."""
    match = MONEY_RE.match(text)
    if not match:
        raise ValueError(f"not a FINIX money string: {text!r}")
    try:
        amount = Decimal(match.group(2))
    except InvalidOperation as exc:
        raise ValueError(str(exc)) from exc
    scaled = amount.scaleb(2)
    if scaled != scaled.to_integral_value():
        raise ValueError(f"sub-cent precision in {text!r}")
    return int(scaled)


def cmd_money(argv) -> int:
    """money "LKR 1250.00" — print minor units."""
    if not argv:
        return EXIT_USAGE
    try:
        print(_money_minor(argv[0]))
    except ValueError:
        return EXIT_MISSING
    return EXIT_OK


def cmd_money_str(argv) -> int:
    """money-str MINOR [CURRENCY] — print the canonical wire form."""
    if not argv:
        return EXIT_USAGE
    try:
        minor = int(argv[0])
    except ValueError:
        return EXIT_MISSING
    currency = argv[1] if len(argv) > 1 else "LKR"
    print(f"{currency} {Decimal(minor).scaleb(-2):.2f}")
    return EXIT_OK


def cmd_money_at(argv) -> int:
    """money-at FILE PATH — read a money field and print its minor units."""
    if len(argv) < 2:
        return EXIT_USAGE
    try:
        value = _walk(_load(argv[0]), argv[1])
        print(_money_minor(str(value)))
    except (OSError, ValueError, KeyError, TypeError):
        return EXIT_MISSING
    return EXIT_OK


def _sha256(*chunks: bytes) -> str:
    digest = hashlib.sha256()
    for chunk in chunks:
        digest.update(chunk)
    return digest.hexdigest()


def cmd_merkle(argv) -> int:
    """merkle FILE — recompute the RFC-6962 root from a ledger proof response.

    Mirrors ``shared-kernel`` ``MerkleTree``: leaves are hashed with a 0x00 prefix and
    internal nodes with 0x01 (the domain separation that stops an internal node being
    passed off as a leaf). Prints ``root=<recomputed>`` and exits non-zero when it does
    not match the ``merkleRoot`` the service claimed.
    """
    if not argv:
        return EXIT_USAGE
    try:
        proof = _load(argv[0])
    except (OSError, ValueError):
        return EXIT_MISSING

    claimed = proof.get("merkleRoot")
    entry_hash = proof.get("entryHash")
    path = proof.get("merklePath") or []
    if not claimed or not entry_hash:
        print("unanchored")
        return EXIT_MISSING

    try:
        computed = _sha256(b"\x00", bytes.fromhex(entry_hash))
        for step in path:
            sibling = bytes.fromhex(step["siblingHash"])
            current = bytes.fromhex(computed)
            if step.get("isLeftSibling"):
                computed = _sha256(b"\x01", sibling, current)
            else:
                computed = _sha256(b"\x01", current, sibling)
    except (ValueError, KeyError, TypeError):
        print("malformed")
        return EXIT_MISSING

    print(f"steps={len(path)} root={computed[:16]}…")
    return EXIT_OK if computed == claimed else EXIT_MISSING


def _redact(value):
    if isinstance(value, dict):
        return {
            key: (REDACTED if str(key).lower() in SECRET_KEYS else _redact(item))
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_redact(item) for item in value]
    if isinstance(value, str):
        return _redact_text(value)
    return value


BEARER_RE = re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}")
JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9._-]{8,}")
# Long base64-ish blobs are masked on sight, because that is the shape a leaked key or
# token has. Bare SHA-256 hex is excluded: hashes are the evidence a judge is here to
# read, and masking them would hide the ledger's own proof material.
LONG_B64_RE = re.compile(r"\b(?![0-9a-f]{64}\b)[A-Za-z0-9+/]{60,}={0,2}\b")


def _redact_text(text: str) -> str:
    text = BEARER_RE.sub(lambda m: f"{m.group(1)} {REDACTED}", text)
    text = JWT_RE.sub(REDACTED, text)
    return LONG_B64_RE.sub(REDACTED, text)


def cmd_redact(argv) -> int:
    """redact FILE [MAXCHARS] — print a body with secret-bearing fields masked.

    Applied to every response excerpt the harness shows, so a failure report can never
    become the thing that leaks a token into CI logs.
    """
    if not argv:
        return EXIT_USAGE
    limit = int(argv[1]) if len(argv) > 1 else 400
    try:
        with open(argv[0], "rb") as fh:
            raw = fh.read()
    except OSError:
        return EXIT_MISSING
    text = raw.decode("utf-8", "replace")
    try:
        rendered = json.dumps(_redact(json.loads(text)), separators=(",", ":"), sort_keys=True)
    except ValueError:
        rendered = _redact_text(text)
    rendered = " ".join(rendered.split())
    if len(rendered) > limit:
        rendered = rendered[:limit] + "…"
    print(rendered)
    return EXIT_OK


def cmd_jsonl(argv) -> int:
    """jsonl STATUS GROUP ID CRITICALITY DETAIL MS — append-ready JSON record."""
    if len(argv) < 6:
        return EXIT_USAGE
    status, group, check_id, criticality, detail, millis = argv[:6]
    print(
        json.dumps(
            {
                "check": check_id,
                "status": status,
                "group": group,
                "criticality": criticality,
                "detail": _redact_text(detail),
                "durationMs": int(millis) if str(millis).isdigit() else None,
            },
            separators=(",", ":"),
        )
    )
    return EXIT_OK


def cmd_report(argv) -> int:
    """report JSONL KEY=VALUE... — assemble the final JSON report on stdout."""
    if not argv:
        return EXIT_USAGE
    meta = {}
    for pair in argv[1:]:
        key, _, value = pair.partition("=")
        meta[key] = int(value) if value.isdigit() else _redact_text(value)
    checks = []
    try:
        with open(argv[0], "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    checks.append(json.loads(line))
    except OSError:
        pass
    meta["checks"] = checks
    print(json.dumps(meta, indent=2, sort_keys=True))
    return EXIT_OK


def cmd_urlencode(argv) -> int:
    if not argv:
        return EXIT_USAGE
    from urllib.parse import quote

    print(quote(argv[0], safe=""))
    return EXIT_OK


def cmd_now_ms(_argv) -> int:
    import time

    print(int(time.time() * 1000))
    return EXIT_OK


COMMANDS = {
    "get": cmd_get,
    "has": cmd_has,
    "len": cmd_len,
    "is-array": cmd_is_array,
    "money": cmd_money,
    "money-str": cmd_money_str,
    "money-at": cmd_money_at,
    "merkle": cmd_merkle,
    "redact": cmd_redact,
    "jsonl": cmd_jsonl,
    "report": cmd_report,
    "urlencode": cmd_urlencode,
    "now-ms": cmd_now_ms,
}


def main(argv: list[str]) -> int:
    if not argv or argv[0] in ("-h", "--help"):
        print(f"usage: {sys.argv[0]} <{'|'.join(sorted(COMMANDS))}> [args]", file=sys.stderr)
        return EXIT_USAGE
    handler = COMMANDS.get(argv[0])
    if handler is None:
        print(f"unknown subcommand: {argv[0]}", file=sys.stderr)
        return EXIT_USAGE
    return handler(argv[1:])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
