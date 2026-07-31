"""FedAvg demonstrator across two simulated bank nodes — not production FL."""
from __future__ import annotations

import numpy as np


def fedavg(weights: list[np.ndarray], sizes: list[int]) -> np.ndarray:
    total = float(sum(sizes))
    acc = np.zeros_like(weights[0], dtype=float)
    for w, n in zip(weights, sizes, strict=True):
        acc += w * (n / total)
    return acc


def demo() -> dict:
    rng = np.random.default_rng(7)
    bank_a = rng.normal(0, 1, size=(4,))
    bank_b = rng.normal(0.5, 1, size=(4,))
    global_w = fedavg([bank_a, bank_b], [100, 80])
    return {
        "banks": 2,
        "algorithm": "FedAvg",
        "global_weights": global_w.tolist(),
        "note": "Demonstrator only — see docs/model-card-risk-ai.md",
    }
