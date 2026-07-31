# FINIX Payment Hub — multi-stage Alpine image
# Build from repo root:
#   docker build -f services/payment-hub/Dockerfile -t finix-payment-hub .

FROM golang:1.22-alpine AS build
WORKDIR /src
RUN apk add --no-cache git ca-certificates
COPY services/payment-hub/go.mod services/payment-hub/go.sum* ./
RUN go mod download
COPY services/payment-hub/ ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/payment-hub .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata \
  && adduser -D -H -u 10001 finix
WORKDIR /app
COPY --from=build /out/payment-hub /app/payment-hub
USER finix
ENV PORT=8092
EXPOSE 8092
ENTRYPOINT ["/app/payment-hub"]
