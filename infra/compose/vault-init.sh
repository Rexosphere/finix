#!/usr/bin/env sh
# Initialise Vault PKI for SVID-style short-lived service certs (security profile).
# Run after `vault` is up: docker compose exec vault sh /vault-init.sh
set -eu
export VAULT_ADDR="${VAULT_ADDR:-http://127.0.0.1:8200}"
# The root token is a generated Docker secret (ADR-0006), mounted into the vault
# container — it is no longer the literal `finix-root` this script used to assume.
export VAULT_TOKEN="${VAULT_TOKEN:-$(cat /run/secrets/vault_root_token)}"

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
