package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/rexosphere/finix/payment-hub/internal/connector"
	"github.com/rexosphere/finix/payment-hub/internal/domain"
	"github.com/rexosphere/finix/payment-hub/internal/pacs008"
	"github.com/rexosphere/finix/payment-hub/internal/store"
)

// Server wires HTTP routes for the payment hub.
type Server struct {
	store *store.Memory
	hub   *connector.Hub
	mux   *http.ServeMux
}

func NewServer(mem *store.Memory, hub *connector.Hub) *Server {
	s := &Server{store: mem, hub: hub, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) Handler() http.Handler { return s.instrument(s.mux) }

// metricsPattern is the ServeMux pattern for the scrape endpoint; the
// instrumentation middleware skips it so scrapes do not measure themselves.
const metricsPattern = "GET /metrics"

func (s *Server) routes() {
	s.mux.Handle(metricsPattern, metricsHandler())
	s.mux.HandleFunc("GET /health", s.health)
	s.mux.HandleFunc("POST /v1/payments", s.createPayment)
	s.mux.HandleFunc("GET /v1/payments/{id}", s.getPayment)
	s.mux.HandleFunc("GET /v1/payments/{id}/pacs008", s.getPacs008)
	// Lightweight gRPC-style JSON path (unary-ish) for demo clients.
	s.mux.HandleFunc("POST /grpc.finix.PaymentHub/CreatePayment", s.createPayment)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"status":  "ok",
		"service": "payment-hub",
	})
}

func (s *Server) createPayment(w http.ResponseWriter, r *http.Request) {
	var req domain.CreatePaymentRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	if err := validateCreate(req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}

	now := time.Now().UTC()
	p := domain.Payment{
		ID:              uuid.NewString(),
		DebtorAccount:   req.DebtorAccount,
		CreditorAccount: req.CreditorAccount,
		AmountMinor:     req.AmountMinor,
		Currency:        strings.ToUpper(req.Currency),
		EndToEndId:      req.EndToEndId,
		Scheme:          req.Scheme,
		Status:          domain.StatusAccepted,
		CreatedAt:       now,
	}

	if err := s.hub.Submit(&p); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	s.store.Save(p)
	writeJSON(w, http.StatusCreated, p)
}

func (s *Server) getPayment(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	p, err := s.store.Get(id)
	if err != nil {
		writeErr(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, p)
}

func (s *Server) getPacs008(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	p, err := s.store.Get(id)
	if err != nil {
		writeErr(w, http.StatusNotFound, err.Error())
		return
	}
	xmlBytes, err := pacs008.Generate(p)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "failed to generate pacs.008")
		return
	}
	w.Header().Set("Content-Type", "application/xml; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(xmlBytes)
}

func validateCreate(req domain.CreatePaymentRequest) error {
	switch {
	case strings.TrimSpace(req.DebtorAccount) == "":
		return errors.New("debtorAccount is required")
	case strings.TrimSpace(req.CreditorAccount) == "":
		return errors.New("creditorAccount is required")
	case req.AmountMinor <= 0:
		return errors.New("amountMinor must be > 0")
	case strings.TrimSpace(req.Currency) == "":
		return errors.New("currency is required")
	case strings.TrimSpace(req.EndToEndId) == "":
		return errors.New("endToEndId is required")
	case !req.Scheme.Valid():
		return errors.New("scheme must be LANKAPAY, VISA, or CBDC")
	default:
		return nil
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
