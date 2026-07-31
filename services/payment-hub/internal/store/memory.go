package store

import (
	"fmt"
	"sync"

	"github.com/rexosphere/finix/payment-hub/internal/domain"
)

// Memory is a thread-safe in-memory payment store for demos.
type Memory struct {
	mu   sync.RWMutex
	byID map[string]domain.Payment
}

func NewMemory() *Memory {
	return &Memory{byID: make(map[string]domain.Payment)}
}

func (m *Memory) Save(p domain.Payment) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.byID[p.ID] = p
}

func (m *Memory) Get(id string) (domain.Payment, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	p, ok := m.byID[id]
	if !ok {
		return domain.Payment{}, fmt.Errorf("payment %s not found", id)
	}
	return p, nil
}
