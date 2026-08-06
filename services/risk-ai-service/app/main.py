from __future__ import annotations

import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.federated import fedavg_demo
from app.metrics import setup_metrics
from app.model import RiskModel, amount_z_score
from app.rules import Decision, decide, rules_engine
from app.shield import AiShield, ServiceHealthSample

ARTIFACT_DIR = Path(os.environ.get("FINIX_MODEL_DIR", Path(__file__).parent / "model_artifacts"))

app = FastAPI(title="FINIX risk-ai-service", version="0.1.0")
setup_metrics(app)
model = RiskModel(ARTIFACT_DIR)
shield = AiShield()
CASES: list[dict[str, Any]] = []


class ScoreRequest(BaseModel):
    transaction_id: str | None = None
    user_id: str | None = None
    from_account_id: str
    to_account_id: str
    amount_minor: int = Field(..., ge=1)
    currency: str = "LKR"
    velocity_1h: int = 0
    new_device: bool = False
    geo_velocity: float = 0.0
    payee_novelty: bool = False
    offline_voucher: bool = False
    hour: int | None = None


class ScoreResponse(BaseModel):
    score: int
    decision: Decision
    reasons: list[str]
    model_score: float
    rules_score: int
    case_id: str | None = None


class LoginScoreRequest(BaseModel):
    keycloak_user_id: str
    fingerprint: str
    ip: str
    identity_score: int | None = None


class HealthSampleRequest(BaseModel):
    service: str
    latency_ms: float
    error_rate: float = Field(..., ge=0.0, le=1.0)


class QuarantineRequest(BaseModel):
    service: str
    reason: str = "manual"


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "service": "risk-ai-service"}


@app.post("/v1/score", response_model=ScoreResponse)
def score_transaction(body: ScoreRequest) -> ScoreResponse:
    hour = body.hour if body.hour is not None else datetime.now(timezone.utc).hour
    z = amount_z_score(body.amount_minor)
    vector = model.feature_vector(
        amount_z=z,
        velocity_1h=body.velocity_1h,
        new_device=body.new_device,
        geo_velocity=body.geo_velocity,
        payee_novelty=body.payee_novelty,
        hour=hour,
        offline_voucher=body.offline_voucher,
    )
    model_score = model.anomaly_score(vector)
    rules_score, reasons = rules_engine(
        amount_minor=body.amount_minor,
        velocity_1h=body.velocity_1h,
        new_device=body.new_device,
        geo_velocity=body.geo_velocity,
        payee_novelty=body.payee_novelty,
        offline_voucher=body.offline_voucher,
        hour=hour,
    )
    # Blend: rules dominate for explainability; model nudges.
    blended = round(0.65 * rules_score + 0.35 * model_score)
    blended = max(0, min(100, blended))
    decision = decide(blended)
    case_id = None
    if decision is Decision.BLOCK:
        case_id = str(uuid4())
        CASES.append(
            {
                "case_id": case_id,
                "transaction_id": body.transaction_id,
                "from_account_id": body.from_account_id,
                "to_account_id": body.to_account_id,
                "amount_minor": body.amount_minor,
                "score": blended,
                "reasons": reasons,
                "created_at": datetime.now(timezone.utc).isoformat(),
                "status": "OPEN",
            }
        )
        reasons = reasons + ["case_opened"]
    return ScoreResponse(
        score=blended,
        decision=decision,
        reasons=reasons,
        model_score=round(model_score, 2),
        rules_score=rules_score,
        case_id=case_id,
    )


@app.post("/v1/login-score")
def login_score(body: LoginScoreRequest) -> dict[str, Any]:
    """IP reputation enrichment for the Keycloak adaptive SPI / identity login-risk."""
    score = body.identity_score if body.identity_score is not None else 0
    reasons: list[str] = []
    # Demo IP reputation: RFC5737 TEST-NET-3 and private ranges are fine; tor-like demo prefix is hot.
    if body.ip.startswith("198.51.100."):
        score += 35
        reasons.append("ip_reputation_bad")
    if body.fingerprint.startswith("unknown-"):
        score += 20
        reasons.append("suspicious_fingerprint")
    score = max(0, min(100, score))
    return {
        "score": score,
        "require_step_up": score >= 40,
        "reasons": reasons,
    }


@app.get("/v1/cases")
def list_cases() -> list[dict[str, Any]]:
    return list(reversed(CASES))


@app.post("/v1/shield/ingest")
def shield_ingest(body: HealthSampleRequest) -> dict[str, Any]:
    rec = shield.ingest(
        ServiceHealthSample(service=body.service, latency_ms=body.latency_ms, error_rate=body.error_rate)
    )
    return {
        "quarantined": rec is not None,
        "record": None
        if rec is None
        else {"service": rec.service, "reason": rec.reason, "at": rec.at.isoformat(), "active": rec.active},
    }


@app.post("/v1/shield/quarantine")
def shield_quarantine(body: QuarantineRequest) -> dict[str, Any]:
    rec = shield.force_quarantine(body.service, body.reason)
    return {"service": rec.service, "reason": rec.reason, "at": rec.at.isoformat(), "active": rec.active}


@app.post("/v1/shield/release/{service}")
def shield_release(service: str) -> dict[str, Any]:
    ok = shield.release(service)
    if not ok:
        raise HTTPException(status_code=404, detail=f"service '{service}' not quarantined")
    return {"service": service, "active": False}


@app.get("/v1/shield/quarantines")
def shield_list() -> list[dict[str, Any]]:
    return [
        {"service": r.service, "reason": r.reason, "at": r.at.isoformat(), "active": r.active}
        for r in shield.active()
    ]


@app.get("/v1/federated/demo")
def federated_demo() -> dict[str, Any]:
    return fedavg_demo.demo()
