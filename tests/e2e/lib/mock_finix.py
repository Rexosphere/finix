#!/usr/bin/env python3
"""A stand-in FINIX edge, used to validate tests/e2e/finix-smoke.sh offline.

This exists so the harness can be exercised end to end — including the money-moving
path, the idempotency filter, the hash chain and the RFC-6962 inclusion proof — on a
machine with no Docker and without ever touching a real deployment.

It re-implements only what the harness observes, and it re-implements it from the same
rules the services use (``risk-ai-service/app/rules.py``, ``shared-kernel`` MerkleTree,
``IdempotencyFilter``), so a harness bug shows up as a failed assertion rather than as
two copies of the same mistake. The Merkle *proof* is built level-by-level here while
the harness folds the path from the leaf, so the two implementations disagree if either
is wrong.

Failure injection for the negative tests:

    MOCK_BREAK=ledger          /ledger/verify reports a broken chain
    MOCK_BREAK=account-number  the farmer account reports a non-seed account number
    MOCK_BREAK=double-debit    a replayed transfer debits a second time

Usage:  mock_finix.py [PORT]   (0 or omitted = ephemeral; the chosen port is printed)
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import threading
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BREAK = os.environ.get("MOCK_BREAK", "")

# Mirrors services/account-service/.../domain/DemoAccounts.kt. Kept as literals on
# purpose: the harness parses the Kotlin file, so if its parser drifts these stop
# matching and the self-test fails, which is the point.
FARMER_USER = "a1111111-1111-4111-8111-111111111101"
SME_USER = "a1111111-1111-4111-8111-111111111102"
ELDER_USER = "a1111111-1111-4111-8111-111111111103"
FARMER_ACCT = "a2222222-2222-4222-8222-222222222201"
SME_ACCT = "a2222222-2222-4222-8222-222222222202"
ELDER_ACCT = "a2222222-2222-4222-8222-222222222203"

ACCOUNTS = {
    FARMER_ACCT: {
        "id": FARMER_ACCT, "ownerUserId": FARMER_USER, "accountNumber": "FINIX-SAV-00000001",
        "type": "SAVINGS", "status": "ACTIVE", "currency": "LKR",
        "available": 2500000, "held": 0, "version": 1,
    },
    SME_ACCT: {
        "id": SME_ACCT, "ownerUserId": SME_USER, "accountNumber": "FINIX-CUR-00000002",
        "type": "CURRENT", "status": "ACTIVE", "currency": "LKR",
        "available": 15000000, "held": 0, "version": 1,
    },
    ELDER_ACCT: {
        "id": ELDER_ACCT, "ownerUserId": ELDER_USER, "accountNumber": "FINIX-SAV-00000003",
        "type": "SAVINGS", "status": "ACTIVE", "currency": "LKR",
        "available": 8000000, "held": 0, "version": 1,
    },
}

PHONES = {"+94771110001": FARMER_ACCT, "+94771110002": SME_ACCT, "+94771110003": ELDER_ACCT}

LOCK = threading.Lock()
IDEMPOTENCY: dict[str, dict] = {}
LEDGER: list[dict] = []
ANCHORS: list[dict] = []
SAGAS: dict[str, dict] = {}
DEVICES: dict[str, dict] = {}
NONCES: set[tuple[str, str]] = set()
LOANS: dict[str, dict] = {}
CASES: list[dict] = []
RISK_CASES: list[dict] = []
PAYMENTS: dict[str, dict] = {}
MESSAGES: list[dict] = []

INDEX_HTML = b"<!doctype html><html><head><title>FINIX</title></head><body>FINIX mesh</body></html>"
LITE_HTML = b"<!doctype html><html><head><title>FINIX Lite</title></head><body>FINIX lite</body></html>"

ZERO_DIGEST = "0" * 64


def money(minor: int, currency: str = "LKR") -> str:
    sign = "-" if minor < 0 else ""
    minor = abs(minor)
    return f"{sign}{currency} {minor // 100}.{minor % 100:02d}"


def money_minor(text: str) -> int:
    match = re.match(r"^\s*([A-Z]{3})\s+(-?\d+)\.(\d{2})\s*$", str(text))
    if not match:
        raise ValueError(f"bad money: {text!r}")
    whole, cents = int(match.group(2)), int(match.group(3))
    return whole * 100 + (cents if whole >= 0 else -cents)


def sha(*chunks: bytes) -> str:
    digest = hashlib.sha256()
    for chunk in chunks:
        digest.update(chunk)
    return digest.hexdigest()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


# --- risk: same additive rules as services/risk-ai-service/app/rules.py --------------
def rules_engine(amount_minor, velocity_1h, new_device, offline_voucher, hour):
    score, reasons = 0, []
    if amount_minor >= 500_000_00:
        score += 35; reasons.append("amount>=500000")
    elif amount_minor >= 100_000_00:
        score += 20; reasons.append("amount>=100000")
    elif amount_minor >= 25_000_00:
        score += 10; reasons.append("amount>=25000")
    if velocity_1h >= 8:
        score += 30; reasons.append("velocity>=8/h")
    elif velocity_1h >= 4:
        score += 15; reasons.append("velocity>=4/h")
    if new_device:
        score += 25; reasons.append("new_device")
    if offline_voucher:
        score += 15; reasons.append("offline_voucher")
    if hour < 5 or hour >= 23:
        score += 10; reasons.append("odd_hours")
    return min(100, score), reasons


MODEL_SCORE = 50.0  # the isolation forest is stubbed at its neutral value


def risk_score(amount_minor, velocity_1h, new_device, offline_voucher):
    hour = datetime.now(timezone.utc).hour
    rules, reasons = rules_engine(amount_minor, velocity_1h, new_device, offline_voucher, hour)
    blended = max(0, min(100, int(round(0.65 * rules + 0.35 * MODEL_SCORE))))
    decision = "block" if blended > 70 else ("step_up" if blended >= 40 else "allow")
    return blended, decision, reasons


# --- ledger --------------------------------------------------------------------------
def post_journal(tx_id: str, lines: list[dict]) -> dict:
    prev = LEDGER[-1]["entryHash"] if LEDGER else ZERO_DIGEST
    payload = json.dumps({"transactionId": tx_id, "lines": lines}, sort_keys=True).encode()
    entry = {
        "id": str(uuid.uuid4()),
        "transactionId": tx_id,
        "sequence": len(LEDGER) + 1,
        "prevHash": prev,
        "entryHash": sha(prev.encode(), payload),
        "recordedAt": now_iso(),
        "lines": lines,
    }
    LEDGER.append(entry)
    return entry


def leaf_hash(entry_hash: str) -> str:
    return sha(b"\x00", bytes.fromhex(entry_hash))


def node_hash(left: str, right: str) -> str:
    return sha(b"\x01", bytes.fromhex(left), bytes.fromhex(right))


def merkle_levels(entry_hashes: list[str]) -> list[list[str]]:
    current = [leaf_hash(h) for h in entry_hashes]
    levels = [current]
    while len(current) > 1:
        nxt = []
        for i in range(0, len(current), 2):
            pair = current[i:i + 2]
            nxt.append(node_hash(pair[0], pair[1]) if len(pair) == 2 else pair[0])
        current = nxt
        levels.append(current)
    return levels


def merkle_proof(entry_hashes: list[str], leaf_index: int):
    levels = merkle_levels(entry_hashes)
    path, index = [], leaf_index
    for level in levels[:-1]:
        sibling = index + 1 if index % 2 == 0 else index - 1
        if sibling < len(level):
            path.append({"siblingHash": level[sibling], "isLeftSibling": sibling < index})
        index //= 2
    return levels[-1][0], path, leaf_index, len(entry_hashes)


def anchor_now():
    start = ANCHORS[-1]["windowEndSeq"] + 1 if ANCHORS else 1
    window = [e for e in LEDGER if e["sequence"] >= start]
    if not window:
        return None
    hashes = [e["entryHash"] for e in window]
    root = merkle_levels(hashes)[-1][0]
    anchor = {
        "id": str(uuid.uuid4()),
        "windowStartSeq": window[0]["sequence"],
        "windowEndSeq": window[-1]["sequence"],
        "merkleRoot": root,
        "entryCount": len(window),
        "signatureBase64": "c2lnbmF0dXJlLXBsYWNlaG9sZGVyLWZvci10ZXN0aW5nLW9ubHk=",
        "publicKeyBase64": "cHVibGljLWtleS1wbGFjZWhvbGRlci1mb3ItdGVzdGluZy1vbmx5",
        "anchoredAt": now_iso(),
    }
    ANCHORS.append(anchor)
    return anchor


# --- problem details -----------------------------------------------------------------
def problem(status: int, code: str, detail: str, **extra):
    body = {"type": f"https://finix.lk/problems/{code}", "title": code, "status": status,
            "detail": detail, "code": code}
    body.update(extra)
    return status, body, "application/problem+json"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "mock-finix/1.0"

    def log_message(self, *_args):  # keep the self-test output readable
        pass

    # -- plumbing ---------------------------------------------------------------------
    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def _send(self, status: int, body, ctype="application/json", headers=None):
        if isinstance(body, (dict, list)):
            raw = json.dumps(body).encode()
        elif isinstance(body, str):
            raw = body.encode()
        else:
            raw = body or b""
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(raw)

    def do_GET(self):
        self._dispatch("GET", b"")

    def do_HEAD(self):
        self._dispatch("HEAD", b"")

    def do_POST(self):
        self._dispatch("POST", self._read_body())

    # -- idempotency (mirrors shared-kernel IdempotencyFilter) ------------------------
    def _idempotency(self, path: str, body: bytes):
        """Returns (replayed_response | None, key, fingerprint) or an error tuple."""
        key = (self.headers.get("Idempotency-Key") or "").strip()
        if not key:
            return ("error", problem(400, "missing-idempotency-key",
                                     "State-changing requests must carry an Idempotency-Key header."))
        fingerprint = sha(self.command.encode(), path.encode(), body)
        with LOCK:
            recorded = IDEMPOTENCY.get(key)
            if recorded is None:
                return ("proceed", key, fingerprint)
            if recorded["fingerprint"] != fingerprint:
                return ("error", problem(422, "idempotency-key-reuse",
                                         "This Idempotency-Key was already used with a different body."))
            return ("replay", recorded)

    def _record(self, key, fingerprint, status, body):
        with LOCK:
            IDEMPOTENCY[key] = {"fingerprint": fingerprint, "status": status, "body": body}

    # -- routing ----------------------------------------------------------------------
    def _dispatch(self, method: str, body: bytes):
        parsed = urlparse(self.path)
        path, query = parsed.path, parse_qs(parsed.query)

        for prefix, handler in (
            ("/api/account", self.svc_account),
            ("/api/orchestrator", self.svc_orchestrator),
            ("/api/ledger", self.svc_ledger),
            ("/api/ussd", self.svc_ussd),
            ("/api/vault", self.svc_vault),
            ("/api/risk", self.svc_risk),
            ("/api/compliance", self.svc_compliance),
            ("/api/loan", self.svc_loan),
            ("/api/notify", self.svc_notify),
            ("/api/pay", self.svc_pay),
        ):
            if path == prefix or path.startswith(prefix + "/"):
                # nginx strips the prefix and forwards the remainder.
                return self._serve(handler, method, path[len(prefix):] or "/", query, body, path)

        if path == "/lite/balance":
            phone = (query.get("phone") or [""])[0]
            account = ACCOUNTS.get(PHONES.get(phone, ""))
            if account is None:
                return self._send(200, "<html><body>Phone not registered.</body></html>", "text/html")
            return self._send(200, f"<html><body><pre>Balance {money(account['available'])}</pre></body></html>",
                              "text/html")
        if path in ("/", "/index.html"):
            return self._send(200, INDEX_HTML, "text/html; charset=utf-8")
        if path == "/lite.html":
            return self._send(200, LITE_HTML, "text/html; charset=utf-8")
        if path.lstrip("/") in {
            "transfer.html", "verify.html", "ussd.html", "offline.html",
            "farmer.html", "sme.html", "elder.html", "manifest.webmanifest", "sw.js",
        }:
            return self._send(200, INDEX_HTML, "text/html; charset=utf-8")
        return self._send(404, {"detail": "not found"}, "application/problem+json")

    def _serve(self, handler, method, path, query, body, full_path):
        # Idempotency is enforced on the JVM services only, exactly as in compose: the
        # USSD gateway, risk, payment hub and notification service disable it.
        needs_key = method == "POST" and not any(
            full_path.startswith(p) for p in ("/api/ussd", "/api/risk", "/api/notify", "/api/pay")
        ) and not path.startswith("/actuator")
        key = fingerprint = None
        if needs_key:
            outcome = self._idempotency(full_path, body)
            if outcome[0] == "error":
                status, payload, ctype = outcome[1]
                return self._send(status, payload, ctype)
            if outcome[0] == "replay":
                recorded = outcome[1]
                if BREAK == "double-debit":
                    # Inject the exact bug the harness is meant to catch: honour the
                    # replay header while running the transfer a second time.
                    handler(method, path, query, body)
                return self._send(recorded["status"], recorded["body"],
                                  headers={"Idempotency-Replayed": "true"})
            _, key, fingerprint = outcome

        try:
            result = handler(method, path, query, body)
        except ValueError as exc:
            return self._send(400, {"detail": str(exc)}, "application/problem+json")
        if result is None:
            return self._send(404, {"detail": "not found"}, "application/problem+json")
        status, payload, ctype = result
        if needs_key and status < 500:
            self._record(key, fingerprint, status, payload)
        return self._send(status, payload, ctype)

    # -- services ---------------------------------------------------------------------
    @staticmethod
    def _account_view(account):
        number = account["accountNumber"]
        if BREAK == "account-number" and account["id"] == FARMER_ACCT:
            number = "REAL-CUSTOMER-99999999"
        return {
            "id": account["id"], "ownerUserId": account["ownerUserId"], "accountNumber": number,
            "type": account["type"], "status": account["status"], "currency": account["currency"],
            "availableBalance": money(account["available"]), "heldBalance": money(account["held"]),
            "ledgerBalance": money(account["available"] + account["held"]),
            "version": account["version"], "openHolds": [],
        }

    def svc_account(self, method, path, query, body):
        if path == "/actuator/health":
            return 200, {"status": "UP"}, "application/json"
        if path == "/api/v1/accounts" and method == "GET":
            owner = (query.get("ownerUserId") or [None])[0]
            if owner is None:
                return problem(400, "missing-parameter", "ownerUserId is required")
            return 200, [self._account_view(a) for a in ACCOUNTS.values()
                         if a["ownerUserId"] == owner], "application/json"
        match = re.fullmatch(r"/api/v1/accounts/([0-9a-fA-F-]{36})", path)
        if match and method == "GET":
            account = ACCOUNTS.get(match.group(1))
            if account is None:
                return problem(404, "not-found", f"account {match.group(1)} not found")
            return 200, self._account_view(account), "application/json"

        if path == "/api/v1/offline/devices" and method == "POST":
            payload = json.loads(body or b"{}")
            device = {
                "deviceId": payload["deviceId"], "ownerUserId": payload["ownerUserId"],
                "accountId": payload["accountId"], "spki": payload["publicKeySpkiBase64"],
                "lastDeviceSeq": 0, "cumulativeMinor": 0, "quarantined": False,
            }
            DEVICES[device["deviceId"]] = device
            return 201, {k: device[k] for k in
                         ("deviceId", "ownerUserId", "accountId", "lastDeviceSeq",
                          "cumulativeMinor", "quarantined")}, "application/json"

        if path == "/api/v1/offline/vouchers/reconcile" and method == "POST":
            return self._reconcile(json.loads(body or b"{}"))
        return None

    def _reconcile(self, payload):
        device = DEVICES.get(payload["deviceId"])
        if device is None:
            return problem(404, "not-found", "device not registered")
        if device["quarantined"]:
            return problem(403, "forbidden", "device is quarantined")

        amount = money_minor(payload["amount"])
        signing_payload = "|".join([
            payload["payerAccountId"], payload["payeeAccountId"], str(amount), "LKR",
            payload["deviceId"], str(payload["deviceSeq"]), payload["nonce"],
            str(payload["validUntilEpochMs"]),
        ]).encode()
        signature_ok = verify_ecdsa(device["spki"], signing_payload, payload["signatureBase64"])
        duplicate = ((payload["deviceId"], payload["nonce"]) in NONCES
                     or payload["deviceSeq"] <= device["lastDeviceSeq"])
        if not signature_ok or duplicate:
            reason = ("invalid-signature" if not signature_ok
                      else ("nonce-reuse" if (payload["deviceId"], payload["nonce"]) in NONCES
                            else "device-seq-gap-or-reuse"))
            device["quarantined"] = True
            return problem(409, "conflict",
                           f"Offline voucher rejected: {reason} (device quarantined)",
                           reason=reason, deviceId=payload["deviceId"])

        payer = ACCOUNTS[payload["payerAccountId"]]
        payee = ACCOUNTS[payload["payeeAccountId"]]
        payer["available"] -= amount
        payee["available"] += amount
        NONCES.add((payload["deviceId"], payload["nonce"]))
        device["lastDeviceSeq"] = payload["deviceSeq"]
        device["cumulativeMinor"] += amount
        return 200, {
            "id": str(uuid.uuid4()), "deviceId": payload["deviceId"],
            "payerAccountId": payload["payerAccountId"], "payeeAccountId": payload["payeeAccountId"],
            "amount": money(amount), "deviceSeq": payload["deviceSeq"],
            "nonce": payload["nonce"], "status": "SETTLED",
        }, "application/json"

    def svc_orchestrator(self, method, path, query, body):
        if path == "/actuator/health":
            return 200, {"status": "UP"}, "application/json"
        if path == "/api/v1/transfers" and method == "POST":
            return self._transfer(json.loads(body or b"{}"))
        match = re.fullmatch(r"/api/v1/transfers/([0-9a-fA-F-]{36})/step-up", path)
        if match and method == "POST":
            saga = SAGAS.get(match.group(1))
            if saga is None:
                return problem(404, "not-found", "saga not found")
            if not json.loads(body or b"{}").get("otpCode"):
                return problem(400, "invalid", "otpCode required")
            if saga["state"] != "AWAITING_STEP_UP":
                return problem(409, "conflict", f"saga is {saga['state']}")
            return self._settle(saga)
        match = re.fullmatch(r"/api/v1/transfers/([0-9a-fA-F-]{36})", path)
        if match and method == "GET":
            saga = SAGAS.get(match.group(1))
            return (200, saga["response"], "application/json") if saga else None
        return None

    def _transfer(self, payload):
        amount = money_minor(payload["amount"])
        saga_id = str(uuid.uuid4())
        score, decision, reasons = risk_score(
            amount, int(payload.get("velocity1h", 0)),
            bool(payload.get("newDevice", False)), bool(payload.get("offlineVoucher", False)),
        )
        saga = {
            "id": saga_id, "from": payload["fromAccountId"], "to": payload["toAccountId"],
            "amount": amount, "state": "INITIATED", "score": score, "decision": decision,
        }
        SAGAS[saga_id] = saga
        if decision == "block":
            RISK_CASES.append({"case_id": str(uuid.uuid4()), "transaction_id": saga_id,
                               "score": score, "reasons": reasons, "status": "OPEN"})
            saga["state"] = "BLOCKED"
            return 201, self._saga_view(saga, f"risk blocked score={score}"), "application/json"
        if decision == "step_up":
            saga["state"] = "AWAITING_STEP_UP"
            return 201, self._saga_view(saga), "application/json"
        return self._settle(saga)

    def _settle(self, saga):
        payer, payee = ACCOUNTS.get(saga["from"]), ACCOUNTS.get(saga["to"])
        if payer is None or payee is None:
            saga["state"] = "FAILED"
            return 201, self._saga_view(saga, "unknown account"), "application/json"
        if payer["available"] < saga["amount"]:
            saga["state"] = "COMPENSATED"
            return 201, self._saga_view(saga, "insufficient funds"), "application/json"
        payer["available"] -= saga["amount"]
        payee["available"] += saga["amount"]
        post_journal(saga["id"], [
            {"accountId": saga["from"], "side": "DEBIT", "amount": money(saga["amount"])},
            {"accountId": saga["to"], "side": "CREDIT", "amount": money(saga["amount"])},
        ])
        saga["state"] = "COMPLETED"
        return 201, self._saga_view(saga), "application/json"

    @staticmethod
    def _saga_view(saga, failure=None):
        return {
            "transferId": saga["id"], "state": saga["state"], "fromAccountId": saga["from"],
            "toAccountId": saga["to"], "amount": money(saga["amount"]),
            "holdId": str(uuid.uuid5(uuid.NAMESPACE_OID, saga["id"])),
            "failureReason": failure, "riskScore": saga["score"], "riskDecision": saga["decision"],
        }

    def svc_ledger(self, method, path, query, body):
        if path == "/actuator/health":
            return 200, {"status": "UP"}, "application/json"
        if path == "/api/v1/ledger/verify":
            if BREAK == "ledger":
                return 200, {"valid": False, "checkedEntries": len(LEDGER),
                             "firstBreakSequence": 1, "detail": "injected break"}, "application/json"
            return 200, {"valid": True, "checkedEntries": len(LEDGER),
                         "firstBreakSequence": None, "detail": None}, "application/json"
        if path == "/api/v1/ledger/anchors" and method == "GET":
            return 200, list(reversed(ANCHORS)), "application/json"
        if path == "/api/v1/ledger/anchors/now" and method == "POST":
            anchor = anchor_now()
            return (200, anchor, "application/json") if anchor else (200, None, "application/json")
        match = re.fullmatch(r"/api/v1/ledger/journals/([0-9a-fA-F-]{36})", path)
        if match:
            for entry in LEDGER:
                if entry["transactionId"] == match.group(1):
                    return 200, entry, "application/json"
            return problem(404, "not-found", "journal not found")
        match = re.fullmatch(r"/api/v1/ledger/proof/([0-9a-fA-F-]{36})", path)
        if match:
            return self._proof(match.group(1))
        return None

    def _proof(self, tx_id):
        entry = next((e for e in LEDGER if e["transactionId"] == tx_id), None)
        if entry is None:
            return problem(404, "not-found", "journal not found")
        anchor = next((a for a in ANCHORS
                       if a["windowStartSeq"] <= entry["sequence"] <= a["windowEndSeq"]), None)
        base = {"transactionId": tx_id, "sequence": entry["sequence"],
                "prevHash": entry["prevHash"], "entryHash": entry["entryHash"]}
        if anchor is None:
            base.update({"merkleRoot": None, "merklePath": [], "leafIndex": None, "treeSize": None,
                         "anchorId": None, "anchorSignatureBase64": None,
                         "anchorPublicKeyBase64": None, "inclusion": entry["entryHash"]})
            return 200, base, "application/json"
        window = [e for e in LEDGER
                  if anchor["windowStartSeq"] <= e["sequence"] <= anchor["windowEndSeq"]]
        hashes = [e["entryHash"] for e in window]
        root, path, leaf_index, size = merkle_proof(hashes, hashes.index(entry["entryHash"]))
        base.update({"merkleRoot": root, "merklePath": path, "leafIndex": leaf_index,
                     "treeSize": size, "anchorId": anchor["id"],
                     "anchorSignatureBase64": anchor["signatureBase64"],
                     "anchorPublicKeyBase64": anchor["publicKeyBase64"], "inclusion": root})
        return 200, base, "application/json"

    def svc_ussd(self, method, path, query, body):
        if path == "/actuator/health":
            return 200, {"status": "UP"}, "application/json"
        if path in ("/ussd", "/api/v1/ussd") and method == "POST":
            form = parse_qs(body.decode())
            phone = (form.get("phoneNumber") or [""])[0]
            text = (form.get("text") or [""])[0]
            account = ACCOUNTS.get(PHONES.get(phone, ""))
            if not text:
                return 200, "CON Welcome to FINIX\n1 Balance\n2 Send\n3 Mini-statement", "text/plain"
            if text == "1" and account:
                return 200, f"END Available {money(account['available'])}", "text/plain"
            return 200, "END Unknown option", "text/plain"
        return None

    def svc_vault(self, method, path, query, body):
        if path == "/actuator/health":
            return 200, {"status": "UP"}, "application/json"
        if path == "/api/v1/vault/ceremony" and method == "GET":
            return 200, {"state": "PENDING", "approvals": [], "threshold": 3}, "application/json"
        return None

    def svc_risk(self, method, path, query, body):
        if path == "/health":
            return 200, {"status": "UP", "service": "risk-ai-service"}, "application/json"
        if path == "/v1/score" and method == "POST":
            payload = json.loads(body or b"{}")
            score, decision, reasons = risk_score(
                int(payload["amount_minor"]), int(payload.get("velocity_1h", 0)),
                bool(payload.get("new_device", False)), bool(payload.get("offline_voucher", False)))
            return 200, {"score": score, "decision": decision, "reasons": reasons,
                         "model_score": MODEL_SCORE, "rules_score": score,
                         "case_id": None}, "application/json"
        if path == "/v1/cases":
            return 200, list(reversed(RISK_CASES)), "application/json"
        return None

    def svc_compliance(self, method, path, query, body):
        if path == "/actuator/health":
            return 200, {"status": "UP"}, "application/json"
        if path == "/api/v1/cases" and method == "GET":
            return 200, CASES, "application/json"
        if path == "/api/v1/screen" and method == "POST":
            payload = json.loads(body or b"{}")
            reasons = []
            if "BLOCKED" in (payload.get("name") or "").upper():
                reasons.append("name_contains_BLOCKED")
            if (payload.get("nic") or "").upper().endswith("X"):
                reasons.append("nic_ends_with_X")
            case_id = None
            if reasons:
                case_id = str(uuid.uuid4())
                CASES.append({"id": case_id, "type": "SANCTIONS", "status": "OPEN"})
            return 200, {"hit": bool(reasons), "reasons": reasons, "caseId": case_id}, "application/json"
        return None

    def svc_loan(self, method, path, query, body):
        if path == "/actuator/health":
            return 200, {"status": "UP"}, "application/json"
        if path == "/api/v1/loans" and method == "GET":
            return 200, list(LOANS.values()), "application/json"
        if path == "/api/v1/loans" and method == "POST":
            payload = json.loads(body or b"{}")
            loan_id = str(uuid.uuid4())
            loan = {"id": loan_id, "borrowerUserId": payload["borrowerUserId"],
                    "accountId": payload["accountId"], "principal": payload["principal"],
                    "termMonths": payload.get("termMonths", 12), "status": "PENDING",
                    "creditScore": None, "riskHint": None, "appliedAt": now_iso(),
                    "decidedAt": None, "schedule": []}
            LOANS[loan_id] = loan
            return 201, loan, "application/json"
        match = re.fullmatch(r"/api/v1/loans/([0-9a-fA-F-]{36})/decide", path)
        if match and method == "POST":
            loan = LOANS.get(match.group(1))
            if loan is None:
                return problem(404, "not-found", "loan not found")
            principal = money_minor(loan["principal"])
            base = 80 if principal <= 5_000_000 else (65 if principal <= 15_000_000 else 50)
            hint = (json.loads(body or b"{}").get("riskHint") or "").upper()
            base += {"LOW": 10, "MEDIUM": -10, "HIGH": -25}.get(hint, 0)
            months = loan["termMonths"]
            per = principal // months
            loan.update({
                "status": "APPROVED" if base >= 60 else "REJECTED",
                "creditScore": base, "riskHint": hint.lower() or None, "decidedAt": now_iso(),
                "schedule": [{"id": str(uuid.uuid4()), "installmentNumber": i + 1,
                              "dueDate": f"2026-{(i % 12) + 1:02d}-01",
                              "amount": money(per), "status": "DUE"} for i in range(months)],
            })
            return 200, loan, "application/json"
        return None

    def svc_notify(self, method, path, query, body):
        if path == "/health":
            return 200, {"status": "ok", "service": "notification-service"}, "application/json"
        if path == "/v1/templates":
            return 200, {"templates": ["transfer_receipt", "step_up_challenge",
                                       "loan_approved", "fraud_alert"]}, "application/json"
        if path == "/v1/notify" and method == "POST":
            payload = json.loads(body or b"{}")
            if payload.get("channel") not in {"sms", "email", "push", "voice"}:
                return 400, {"error": "bad channel"}, "application/json"
            message = {"id": str(uuid.uuid4()), "channel": payload["channel"],
                       "locale": payload.get("locale", "en"), "template": payload["template"],
                       "to": payload["to"], "subject": "FINIX",
                       "body": f"[{payload.get('locale', 'en')}] receipt "
                               f"{payload.get('vars', {}).get('amount', '')}"}
            MESSAGES.append(message)
            return 201, message, "application/json"
        if path == "/v1/messages":
            return 200, {"messages": MESSAGES}, "application/json"
        return None

    def svc_pay(self, method, path, query, body):
        if path == "/health":
            return 200, {"status": "ok", "service": "payment-hub"}, "application/json"
        if path == "/v1/payments" and method == "POST":
            payload = json.loads(body or b"{}")
            payment = {"id": str(uuid.uuid4()), "debtorAccount": payload["debtorAccount"],
                       "creditorAccount": payload["creditorAccount"],
                       "amountMinor": payload["amountMinor"], "currency": payload["currency"],
                       "endToEndId": payload["endToEndId"], "scheme": payload["scheme"],
                       "status": "ACCEPTED", "createdAt": now_iso()}
            PAYMENTS[payment["id"]] = payment
            return 201, payment, "application/json"
        match = re.fullmatch(r"/v1/payments/([0-9a-fA-F-]{36})/pacs008", path)
        if match:
            payment = PAYMENTS.get(match.group(1))
            if payment is None:
                return problem(404, "not-found", "payment not found")
            xml = (f'<?xml version="1.0" encoding="UTF-8"?><Document '
                   f'xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"><FIToFICstmrCdtTrf>'
                   f'<PmtId><EndToEndId>{payment["endToEndId"]}</EndToEndId></PmtId>'
                   f'</FIToFICstmrCdtTrf></Document>')
            return 200, xml, "application/xml; charset=utf-8"
        match = re.fullmatch(r"/v1/payments/([0-9a-fA-F-]{36})", path)
        if match:
            payment = PAYMENTS.get(match.group(1))
            return (200, payment, "application/json") if payment else None
        return None


def verify_ecdsa(spki_b64: str, payload: bytes, signature_b64: str) -> bool:
    """Real P-256/SHA-256 verification when `cryptography` is installed.

    This is what proves the harness builds the signing payload exactly as
    OfflineVoucherSigning.payload does; without the library the check degrades to a
    presence test and the self-test says so.
    """
    import base64

    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
    except ImportError:
        return bool(signature_b64)
    try:
        key = serialization.load_der_public_key(base64.b64decode(spki_b64))
        key.verify(base64.b64decode(signature_b64), payload, ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def main() -> int:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(server.server_address[1], flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
