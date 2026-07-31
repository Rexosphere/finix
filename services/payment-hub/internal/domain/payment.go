package domain

import "time"

// Scheme identifies the rail used to settle a payment.
type Scheme string

const (
	SchemeLankaPay Scheme = "LANKAPAY"
	SchemeVisa     Scheme = "VISA"
	SchemeCBDC     Scheme = "CBDC"
)

func (s Scheme) Valid() bool {
	switch s {
	case SchemeLankaPay, SchemeVisa, SchemeCBDC:
		return true
	default:
		return false
	}
}

// Status is the lifecycle state of a simulated payment.
type Status string

const (
	StatusAccepted Status = "ACCEPTED"
	StatusSettled  Status = "SETTLED"
	StatusRejected Status = "REJECTED"
)

// CreatePaymentRequest is the JSON body for POST /v1/payments.
type CreatePaymentRequest struct {
	DebtorAccount   string `json:"debtorAccount"`
	CreditorAccount string `json:"creditorAccount"`
	AmountMinor     int64  `json:"amountMinor"`
	Currency        string `json:"currency"`
	EndToEndId      string `json:"endToEndId"`
	Scheme          Scheme `json:"scheme"`
}

// Payment is a stored credit-transfer instruction.
type Payment struct {
	ID              string    `json:"id"`
	DebtorAccount   string    `json:"debtorAccount"`
	CreditorAccount string    `json:"creditorAccount"`
	AmountMinor     int64     `json:"amountMinor"`
	Currency        string    `json:"currency"`
	EndToEndId      string    `json:"endToEndId"`
	Scheme          Scheme    `json:"scheme"`
	Status          Status    `json:"status"`
	ConnectorRef    string    `json:"connectorRef"`
	CreatedAt       time.Time `json:"createdAt"`
	SettledAt       time.Time `json:"settledAt,omitempty"`
}
