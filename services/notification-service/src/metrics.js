/**
 * Prometheus instrumentation for the notification service.
 *
 * Metric and label names mirror Micrometer's Spring Boot conventions
 * (http_server_requests_seconds with uri/method/status/outcome) so the same
 * Grafana panels cover the JVM services and this one.
 */
import { Registry, Histogram, collectDefaultMetrics } from "prom-client";

export const registry = new Registry();
collectDefaultMetrics({ register: registry });

const requestDuration = new Histogram({
  name: "http_server_requests_seconds",
  help: "Duration of inbound HTTP requests.",
  labelNames: ["method", "uri", "status", "outcome"],
  buckets: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10],
  registers: [registry],
});

function outcome(status) {
  if (status >= 500) return "SERVER_ERROR";
  if (status >= 400) return "CLIENT_ERROR";
  if (status >= 300) return "REDIRECTION";
  if (status >= 200) return "SUCCESS";
  return "INFORMATIONAL";
}

/**
 * Times every request, labelling it with the matched Express route rather
 * than the raw path — an id in the URL would otherwise give the series
 * unbounded cardinality.
 */
export function metricsMiddleware(req, res, next) {
  const end = requestDuration.startTimer();
  res.on("finish", () => {
    const uri = req.route ? `${req.baseUrl}${req.route.path}` : "UNKNOWN";
    end({
      method: req.method,
      uri,
      status: String(res.statusCode),
      outcome: outcome(res.statusCode),
    });
  });
  next();
}

export async function metricsHandler(_req, res) {
  res.set("Content-Type", registry.contentType);
  res.end(await registry.metrics());
}
