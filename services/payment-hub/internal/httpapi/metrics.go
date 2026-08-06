package httpapi

import (
	"net/http"
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Metric and label names deliberately mirror Micrometer's Spring Boot
// conventions (http_server_requests_seconds, uri/method/status/outcome) so
// that one Grafana panel covers the JVM services and this one alike.
var (
	registry = prometheus.NewRegistry()

	requestDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_server_requests_seconds",
			Help:    "Duration of inbound HTTP requests.",
			Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
		},
		[]string{"method", "uri", "status", "outcome"},
	)
)

func init() {
	registry.MustRegister(
		collectors.NewGoCollector(),
		collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
		requestDuration,
	)
}

// metricsHandler serves the scrape endpoint from the private registry.
func metricsHandler() http.Handler {
	return promhttp.HandlerFor(registry, promhttp.HandlerOpts{Registry: registry})
}

// statusRecorder captures the status code, which http.ResponseWriter
// otherwise swallows.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	return r.ResponseWriter.Write(b)
}

// instrument times every request, labelling it with the matched ServeMux
// pattern rather than the raw path — a payment id in the URI would
// otherwise give the series unbounded cardinality.
func (s *Server) instrument(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, pattern := s.mux.Handler(r)
		if pattern == metricsPattern {
			next.ServeHTTP(w, r)
			return
		}

		uri := pattern
		if uri == "" {
			uri = "UNKNOWN"
		}

		rec := &statusRecorder{ResponseWriter: w}
		start := time.Now()
		next.ServeHTTP(rec, r)

		if rec.status == 0 {
			rec.status = http.StatusOK
		}
		requestDuration.WithLabelValues(
			r.Method,
			uri,
			strconv.Itoa(rec.status),
			outcome(rec.status),
		).Observe(time.Since(start).Seconds())
	})
}

func outcome(status int) string {
	switch {
	case status >= 500:
		return "SERVER_ERROR"
	case status >= 400:
		return "CLIENT_ERROR"
	case status >= 300:
		return "REDIRECTION"
	case status >= 200:
		return "SUCCESS"
	default:
		return "INFORMATIONAL"
	}
}
