# FINIX Payment Hub — multi-stage Alpine image
# Build from repo root:
#   docker build -f services/payment-hub/Dockerfile -t finix-payment-hub .

FROM golang:1.22-alpine@sha256:1699c10032ca2582ec89a24a1312d986a3f094aed3d5c1147b19880afe40e052 AS build
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
