"""Train IsolationForest on a deterministic synthetic dataset and persist artifacts."""
from __future__ import annotations

import json
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import IsolationForest

FEATURES = [
    "amount_z",
    "velocity_1h",
    "new_device",
    "geo_velocity",
    "payee_novelty",
    "hour_sin",
    "hour_cos",
    "offline_voucher",
]

SEED = 42
ARTIFACT_DIR = Path(__file__).resolve().parents[1] / "app" / "model_artifacts"


def synthesize(n: int = 2000, seed: int = SEED) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    # Normal traffic
    normal = np.column_stack(
        [
            rng.normal(0.0, 1.0, n),  # amount_z
            rng.poisson(1.5, n).astype(float),  # velocity
            rng.binomial(1, 0.05, n).astype(float),  # new_device
            rng.normal(0.2, 0.3, n).clip(0, 5),  # geo_velocity
            rng.binomial(1, 0.15, n).astype(float),  # payee_novelty
            np.sin(2 * np.pi * rng.integers(0, 24, n) / 24),
            np.cos(2 * np.pi * rng.integers(0, 24, n) / 24),
            rng.binomial(1, 0.02, n).astype(float),  # offline
        ]
    )
    # Anomalies
    m = max(40, n // 20)
    anomalies = np.column_stack(
        [
            rng.normal(4.5, 1.0, m),
            rng.poisson(12, m).astype(float),
            np.ones(m),
            rng.normal(3.0, 0.8, m).clip(0, 8),
            np.ones(m),
            np.sin(2 * np.pi * rng.integers(0, 24, m) / 24),
            np.cos(2 * np.pi * rng.integers(0, 24, m) / 24),
            rng.binomial(1, 0.4, m).astype(float),
        ]
    )
    x = np.vstack([normal, anomalies])
    y = np.concatenate([np.zeros(n), np.ones(m)])
    return x, y


def train() -> Path:
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    x, _ = synthesize()
    model = IsolationForest(
        n_estimators=100,
        contamination=0.05,
        random_state=SEED,
        n_jobs=1,
    )
    model.fit(x)
    path = ARTIFACT_DIR / "isolation_forest.joblib"
    joblib.dump(model, path)
    meta = {
        "features": FEATURES,
        "seed": SEED,
        "algorithm": "IsolationForest",
        "contamination": 0.05,
        "n_estimators": 100,
    }
    (ARTIFACT_DIR / "meta.json").write_text(json.dumps(meta, indent=2))
    return path


if __name__ == "__main__":
    out = train()
    print(f"wrote {out}")
