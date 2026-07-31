package httpapi_test

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/rexosphere/finix/payment-hub/internal/connector"
	"github.com/rexosphere/finix/payment-hub/internal/domain"
	"github.com/rexosphere/finix/payment-hub/internal/httpapi"
	"github.com/rexosphere/finix/payment-hub/internal/store"
)

func TestCreatePaymentAndPacs008ContainsEndToEndId(t *testing.T) {
	srv := httpapi.NewServer(store.NewMemory(), connector.NewHub())
	body := `{
		"debtorAccount": "LK-1001",
		"creditorAccount": "LK-2002",
		"amountMinor": 250050,
		"currency": "LKR",
		"endToEndId": "E2E-DEMO-99",
		"scheme": "LANKAPAY"
	}`

	createReq := httptest.NewRequest(http.MethodPost, "/v1/payments", bytes.NewBufferString(body))
	createReq.Header.Set("Content-Type", "application/json")
	createRec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusCreated {
		t.Fatalf("create status = %d, body = %s", createRec.Code, createRec.Body.String())
	}

	var payment domain.Payment
	if err := json.Unmarshal(createRec.Body.Bytes(), &payment); err != nil {
		t.Fatalf("decode payment: %v", err)
	}
	if payment.ID == "" {
		t.Fatal("expected payment id")
	}
	if payment.EndToEndId != "E2E-DEMO-99" {
		t.Fatalf("endToEndId = %q", payment.EndToEndId)
	}
	if payment.Status != domain.StatusSettled {
		t.Fatalf("status = %q, want SETTLED", payment.Status)
	}
	if !strings.HasPrefix(payment.ConnectorRef, "LP-") {
		t.Fatalf("connectorRef = %q, want LP- prefix", payment.ConnectorRef)
	}

	pacsReq := httptest.NewRequest(http.MethodGet, "/v1/payments/"+payment.ID+"/pacs008", nil)
	pacsRec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(pacsRec, pacsReq)

	if pacsRec.Code != http.StatusOK {
		t.Fatalf("pacs008 status = %d", pacsRec.Code)
	}
	xmlBody, err := io.ReadAll(pacsRec.Body)
	if err != nil {
		t.Fatal(err)
	}
	xmlStr := string(xmlBody)
	if !strings.Contains(xmlStr, "<EndToEndId>E2E-DEMO-99</EndToEndId>") {
		t.Fatalf("pacs.008 missing EndToEndId; got:\n%s", xmlStr)
	}
	if !strings.Contains(xmlStr, "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08") {
		t.Fatal("pacs.008 missing ISO namespace")
	}
}
