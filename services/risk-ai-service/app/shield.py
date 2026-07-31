from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import Lock


@dataclass
class ServiceHealthSample:
    service: str
    latency_ms: float
    error_rate: float
    at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


@dataclass
class QuarantineRecord:
    service: str
    reason: str
    at: datetime
    active: bool = True


class AiShield:
    """Watches latency/error streams and quarantines anomalous services."""

    def __init__(self, latency_ms_threshold: float = 800.0, error_rate_threshold: float = 0.25):
        self.latency_ms_threshold = latency_ms_threshold
        self.error_rate_threshold = error_rate_threshold
        self._lock = Lock()
        self._samples: list[ServiceHealthSample] = []
        self._quarantines: dict[str, QuarantineRecord] = {}

    def ingest(self, sample: ServiceHealthSample) -> QuarantineRecord | None:
        with self._lock:
            self._samples.append(sample)
            if len(self._samples) > 500:
                self._samples = self._samples[-500:]
            if sample.latency_ms >= self.latency_ms_threshold or sample.error_rate >= self.error_rate_threshold:
                rec = QuarantineRecord(
                    service=sample.service,
                    reason=(
                        f"latency_ms={sample.latency_ms:.0f} error_rate={sample.error_rate:.2f}"
                    ),
                    at=sample.at,
                    active=True,
                )
                self._quarantines[sample.service] = rec
                return rec
            return None

    def force_quarantine(self, service: str, reason: str) -> QuarantineRecord:
        with self._lock:
            rec = QuarantineRecord(service=service, reason=reason, at=datetime.now(timezone.utc), active=True)
            self._quarantines[service] = rec
            return rec

    def release(self, service: str) -> bool:
        with self._lock:
            rec = self._quarantines.get(service)
            if not rec:
                return False
            rec.active = False
            return True

    def active(self) -> list[QuarantineRecord]:
        with self._lock:
            return [r for r in self._quarantines.values() if r.active]

    def is_quarantined(self, service: str) -> bool:
        with self._lock:
            rec = self._quarantines.get(service)
            return bool(rec and rec.active)
