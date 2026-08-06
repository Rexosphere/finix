# FINIX Payment Hub — multi-stage Alpine image
# Build from repo root:
#   docker build -f services/payment-hub/Dockerfile -t finix-payment-hub .

# Tracks the `go` directive in go.mod (1.25). A builder older than that makes
# the toolchain download a second Go at build time — slow, and impossible in an
# air-gapped build.
FROM golang:1.26-alpine@sha256:0178a641fbb4858c5f1b48e34bdaabe0350a330a1b1149aabd498d0699ff5fb2 AS build
WORKDIR /src
RUN apk add --no-cache git ca-certificates
COPY services/payment-hub/go.mod services/payment-hub/go.sum* ./
RUN go mod download
COPY services/payment-hub/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/payment-hub .

FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b
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
