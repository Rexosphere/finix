#!/usr/bin/env sh
# Initialise Vault PKI for SVID-style short-lived service certs (security profile).
# Run after `vault` is up: docker compose exec vault sh /vault-init.sh
set -eu
export VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"
export VAULT_TOKEN="${VAULT_TOKEN:-finix-root}"

vault secrets enable -path=pki pki || true
vault secrets tune -max-lease-ttl=8760h pki
vault write -field=certificate pki/root/generate/internal \
  common_name="FINIX Service CA" ttl=8760h >/tmp/ca.crt || true
vault write pki/config/urls \
  issuing_certificates="${VAULT_ADDR}/v1/pki/ca" \
  crl_distribution_points="${VAULT_ADDR}/v1/pki/crl"
vault write pki/roles/finix-service \
  allowed_domains="finix.internal" \
  allow_subdomains=true \
  max_ttl=10m \
  generate_lease=true
echo "Vault PKI ready: role pki/roles/finix-service (10m TTL)"
