#!/bin/sh
# Seed Vault with the credentials the services need, and issue one AppRole per
# service (ADR-0006).
#
# Trust bootstrap, stated plainly: the values seeded here are read from the same
# mounted Docker secrets the services would otherwise use directly. That is what
# makes Vault the *runtime* source of truth without the two sources ever being
# able to disagree — and it is deliberately the only place both are visible.
# In a real deployment this step is an operator-run key ceremony
# (docs/runbooks/key-ceremony.md), not a container.
#
# Dev-mode Vault keeps everything in memory, so this runs on every start and
# must stay idempotent.
set -eu

export VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
VAULT_TOKEN="$(cat /run/secrets/vault_root_token)"
export VAULT_TOKEN

echo "==> Waiting for Vault at $VAULT_ADDR"
i=0
until vault status >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -gt 60 ]; then
    echo "Vault did not become ready in 60s" >&2
    exit 1
  fi
  sleep 1
done

# Dev mode already mounts kv-v2 at secret/ and may already have approle from a
# previous run of this script; both enables are best-effort by design.
vault secrets enable -path=secret -version=2 kv >/dev/null 2>&1 || true
vault auth enable approle >/dev/null 2>&1 || true

# seed <service> <secret-file> <approle-output-dir>
seed() {
  svc="$1"
  secret_file="$2"
  out_dir="$3"

  vault kv put "secret/finix/$svc" \
    "spring.datasource.password=$(cat "$secret_file")" >/dev/null

  # Least privilege: each role can read exactly its own path, nothing else.
  vault policy write "finix-$svc" - >/dev/null <<EOF
path "secret/data/finix/$svc" {
  capabilities = ["read"]
}
path "secret/metadata/finix/$svc" {
  capabilities = ["read"]
}
EOF

  vault write "auth/approle/role/$svc" \
    token_policies="finix-$svc" \
    token_ttl=1h \
    token_max_ttl=4h \
    secret_id_ttl=24h >/dev/null

  role_id="$(vault read -field=role_id "auth/approle/role/$svc/role-id")"
  secret_id="$(vault write -f -field=secret_id "auth/approle/role/$svc/secret-id")"

  # File names are Spring property names: the service imports this directory as
  # a configtree, so no AppRole credential is ever passed as an env var.
  mkdir -p "$out_dir"
  printf '%s' "$role_id" > "$out_dir/spring.cloud.vault.app-role.role-id"
  printf '%s' "$secret_id" > "$out_dir/spring.cloud.vault.app-role.secret-id"
  chmod 444 "$out_dir"/spring.cloud.vault.app-role.*

  echo "    seeded secret/finix/$svc and issued AppRole $svc"
}

echo "==> Seeding service credentials"
seed identity-service /run/secrets/db_identity_password /approle/identity-service
seed vault-service /run/secrets/db_vault_svc_password /approle/vault-service

echo "==> Vault bootstrap complete"
