from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import joblib
import numpy as np

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


class RiskModel:
    def __init__(self, artifact_dir: Path):
        self.artifact_dir = artifact_dir
        model_path = artifact_dir / "isolation_forest.joblib"
        if not model_path.exists():
            scripts = Path(__file__).resolve().parents[1] / "scripts"
            sys.path.insert(0, str(scripts.parent))
            from scripts.train_model import train

            train()
        self.model = joblib.load(model_path)
        meta_path = artifact_dir / "meta.json"
        self.meta = json.loads(meta_path.read_text()) if meta_path.exists() else {"features": FEATURES}

    def feature_vector(
        self,
        *,
        amount_z: float,
        velocity_1h: int,
        new_device: bool,
        geo_velocity: float,
        payee_novelty: bool,
        hour: int,
        offline_voucher: bool,
    ) -> np.ndarray:
        return np.array(
            [
                [
                    amount_z,
                    float(velocity_1h),
                    1.0 if new_device else 0.0,
                    geo_velocity,
                    1.0 if payee_novelty else 0.0,
                    math.sin(2 * math.pi * hour / 24),
                    math.cos(2 * math.pi * hour / 24),
                    1.0 if offline_voucher else 0.0,
                ]
            ],
            dtype=float,
        )

    def anomaly_score(self, vector: np.ndarray) -> float:
        raw = float(-self.model.decision_function(vector)[0])
        return max(0.0, min(100.0, 50.0 + raw * 40.0))


def amount_z_score(amount_minor: int, mean_minor: float = 15_000_00, std_minor: float = 20_000_00) -> float:
    if std_minor <= 0:
        return 0.0
    return (amount_minor - mean_minor) / std_minor
