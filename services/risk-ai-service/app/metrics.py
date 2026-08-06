"""Prometheus instrumentation for the risk AI service.

Metric and label names mirror Micrometer's Spring Boot conventions
(``http_server_requests_seconds`` with uri/method/status/outcome) so the same
Grafana panels cover the JVM services and this one.

Uses the default registry, which already carries the process, platform and GC
collectors — a private registry would silently drop those.
"""

from __future__ import annotations

import time

from fastapi import FastAPI, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, REGISTRY, Histogram, generate_latest
from starlette.middleware.base import BaseHTTPMiddleware

REQUEST_DURATION = Histogram(
    "http_server_requests_seconds",
    "Duration of inbound HTTP requests.",
    labelnames=("method", "uri", "status", "outcome"),
    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10),
)


def _outcome(status: int) -> str:
    if status >= 500:
        return "SERVER_ERROR"
    if status >= 400:
        return "CLIENT_ERROR"
    if status >= 300:
        return "REDIRECTION"
    if status >= 200:
        return "SUCCESS"
    return "INFORMATIONAL"


def _route(request: Request) -> str:
    """Matched path template, not the raw path.

    A transaction id in the URL would otherwise give the series unbounded
    cardinality. Only populated once the router has matched, so this must be
    read after the downstream handler has run.
    """
    route = request.scope.get("route")
    return getattr(route, "path", None) or "UNKNOWN"


class _MetricsMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if request.url.path == "/metrics":
            return await call_next(request)

        start = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            # The client still sees a 500, so record it rather than losing
            # the observation entirely.
            REQUEST_DURATION.labels(
                request.method, _route(request), "500", "SERVER_ERROR"
            ).observe(time.perf_counter() - start)
            raise

        REQUEST_DURATION.labels(
            request.method,
            _route(request),
            str(response.status_code),
            _outcome(response.status_code),
        ).observe(time.perf_counter() - start)
        return response


def setup_metrics(app: FastAPI) -> None:
    app.add_middleware(_MetricsMiddleware)

    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        return Response(generate_latest(REGISTRY), media_type=CONTENT_TYPE_LATEST)
