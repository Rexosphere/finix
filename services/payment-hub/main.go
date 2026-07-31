/*
FINIX Payment Hub — Go service simulating LankaPay / Visa / CBDC connectors
with ISO 20022 pacs.008 credit-transfer messages.

Run locally:
  cd services/payment-hub && go run .
  # listens on :8092

Smoke:
  curl -s localhost:8092/health
  curl -s -X POST localhost:8092/v1/payments -H 'Content-Type: application/json' \
    -d '{"debtorAccount":"LK001","creditorAccount":"LK002","amountMinor":150000,"currency":"LKR","endToEndId":"E2E-42","scheme":"LANKAPAY"}'
  curl -s localhost:8092/v1/payments/<id>/pacs008

Docker:
  docker build -f services/payment-hub/Dockerfile -t finix-payment-hub .
  docker run --rm -p 8092:8092 finix-payment-hub
*/
package main

import (
	"log"
	"net/http"
	"os"

	"github.com/rexosphere/finix/payment-hub/internal/connector"
	"github.com/rexosphere/finix/payment-hub/internal/httpapi"
	"github.com/rexosphere/finix/payment-hub/internal/store"
)

func main() {
	addr := getenv("PORT", "8092")
	if addr[0] != ':' {
		addr = ":" + addr
	}

	mem := store.NewMemory()
	hub := connector.NewHub()
	srv := httpapi.NewServer(mem, hub)

	log.Printf("finix payment-hub listening on %s", addr)
	if err := http.ListenAndServe(addr, srv.Handler()); err != nil {
		log.Fatal(err)
	}
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
