# FINIX Payment Hub — multi-stage Alpine image
# Build from repo root:
#   docker build -f services/payment-hub/Dockerfile -t finix-payment-hub .

# Tracks the `go` directive in go.mod (1.25). A builder older than that makes
# the toolchain download a second Go at build time — slow, and impossible in an
# air-gapped build.
FROM golang:1.25-alpine@sha256:56961d79ea8129efddcc0b8643fd8a5416b4e6228cfd477e3fd61deb2672c587 AS build
WORKDIR /src
RUN apk add --no-cache git ca-certificates
COPY services/payment-hub/go.mod services/payment-hub/go.sum* ./
RUN go mod download
COPY services/payment-hub/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/payment-hub .

FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
RUN apk add --no-cache ca-certificates tzdata \
  && adduser -D -H -u 10001 finix
WORKDIR /app
COPY --from=build /out/payment-hub /app/payment-hub
USER finix
ENV PORT=8092
EXPOSE 8092
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
  CMD wget -q --spider http://127.0.0.1:8092/health || exit 1
ENTRYPOINT ["/app/payment-hub"]
