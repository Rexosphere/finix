package connector

import (
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/rexosphere/finix/payment-hub/internal/domain"
)

// Hub fans out to simulated LankaPay / Visa / CBDC connectors.
type Hub struct{}

func NewHub() *Hub { return &Hub{} }

// Submit routes the payment to the scheme-specific connector and returns a
// connector reference plus settled status (always settles in this demo).
func (h *Hub) Submit(p *domain.Payment) error {
	ref, err := h.route(p)
	if err != nil {
		p.Status = domain.StatusRejected
		return err
	}
	p.ConnectorRef = ref
	p.Status = domain.StatusSettled
	p.SettledAt = time.Now().UTC()
	return nil
}

func (h *Hub) route(p *domain.Payment) (string, error) {
	switch p.Scheme {
	case domain.SchemeLankaPay:
		return fmt.Sprintf("LP-%s", uuid.NewString()[:8]), nil
	case domain.SchemeVisa:
		return fmt.Sprintf("VISA-%s", uuid.NewString()[:8]), nil
	case domain.SchemeCBDC:
		return fmt.Sprintf("CBDC-%s", uuid.NewString()[:8]), nil
	default:
		return "", fmt.Errorf("unsupported scheme %q", p.Scheme)
	}
}
