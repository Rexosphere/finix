from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class Decision(str, Enum):
    ALLOW = "allow"
    STEP_UP = "step_up"
    BLOCK = "block"


@dataclass(frozen=True)
class ScoreResult:
    score: int
    decision: Decision
    reasons: list[str]
    model_score: float
    rules_score: int


def decide(score: int) -> Decision:
    if score > 70:
        return Decision.BLOCK
    if score >= 40:
        return Decision.STEP_UP
    return Decision.ALLOW


def rules_engine(
    *,
    amount_minor: int,
    velocity_1h: int,
    new_device: bool,
    geo_velocity: float,
    payee_novelty: bool,
    offline_voucher: bool,
    hour: int,
) -> tuple[int, list[str]]:
    """Deterministic additive rules — transparent, auditable, and demo-stable."""
    score = 0
    reasons: list[str] = []

    if amount_minor >= 500_000_00:  # LKR 500,000
        score += 35
        reasons.append("amount>=500000")
    elif amount_minor >= 100_000_00:
        score += 20
        reasons.append("amount>=100000")
    elif amount_minor >= 25_000_00:
        score += 10
        reasons.append("amount>=25000")

    if velocity_1h >= 8:
        score += 30
        reasons.append("velocity>=8/h")
    elif velocity_1h >= 4:
        score += 15
        reasons.append("velocity>=4/h")

    if new_device:
        score += 25
        reasons.append("new_device")

    if geo_velocity >= 2.5:
        score += 20
        reasons.append("geo_velocity_high")

    if payee_novelty:
        score += 10
        reasons.append("novel_payee")

    if offline_voucher:
        score += 15
        reasons.append("offline_voucher")

    if hour < 5 or hour >= 23:
        score += 10
        reasons.append("odd_hours")

    return min(100, score), reasons
