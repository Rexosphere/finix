# 6. Generated Docker secrets, with Vault as the runtime source of truth

- Status: Accepted
- Date: 2026-08-06

## Context

Until now every credential in the stack was a literal in `infra/compose/docker-compose.yml`:
`POSTGRES_PASSWORD: finix`, `SPRING_DATASOURCE_PASSWORD: identity`, `KC_BOOTSTRAP_ADMIN_PASSWORD:
admin`, `VAULT_DEV_ROOT_TOKEN_ID: finix-root`. The same file is what production runs, so the
public repository held the production credentials of a banking demo whose pitch is zero trust.

Three properties had to hold at once:

1. **Nothing secret in git** — including in a file a judge is invited to read.
2. **`make demo` stays one command** — a new user must not have to invent credentials.
3. **Vault is the source of truth where it runs** — the platform ships HashiCorp Vault and claims
   secrets management; that claim should be true at runtime, not aspirational.

Delivering (3) for all twelve services means a Vault client in Kotlin, Go, Python and Node, and a
per-language AppRole login path — days of work for services that, in several cases, hold no
credential at all.

## Decision

**Two layers, one value.**

*Layer 1 — Docker secrets, universally.* `scripts/gen-secrets.sh` generates one random hex value
per credential into `infra/compose/secrets/` (gitignored, mode 0700 dir / 0444 files) and compose
mounts them as Docker secrets. Every component consumes them through its own file-based mechanism,
with no application code change:

| Component | Mechanism |
|---|---|
| Spring services | `spring.config.import=configtree:/run/secrets/` — the file name *is* the property name |
| Postgres | `POSTGRES_PASSWORD_FILE`, plus `init-databases.sh` creating each role from its secret file |
| Grafana | `GF_SECURITY_ADMIN_PASSWORD__FILE` |
| postgres_exporter | `DATA_SOURCE_PASS_FILE` |
| Keycloak | entrypoint wrapper exporting from the file — Keycloak has no `_FILE` support ([keycloak#10816](https://github.com/keycloak/keycloak/issues/10816)) |
| Vault | `-dev-root-token-id` read from the file at start |

*Layer 2 — Vault, for the services that hold real credentials.* `infra/compose/vault-bootstrap.sh`
seeds `secret/finix/<service>` and issues one least-privilege AppRole per service, writing
`role-id`/`secret-id` into a per-service volume as *files named after Spring properties*, so even
the AppRole credentials arrive by configtree rather than by environment variable.
`docker-compose.vault.yml` then points `identity-service` and `vault-service` at
`vault://secret/finix/<service>`.

**The bootstrap seeds Vault from the Docker secret files.** That is the load-bearing detail: the
two layers cannot disagree, so precedence between them never has to be reasoned about, and the
fallback path (no Vault running) is the same value rather than a different one.

## Consequences

**Positive.** `grep -rE 'PASSWORD|TOKEN|SECRET' infra/compose/docker-compose.yml` returns only
`_FILE` and `${VAR}` references — enforced by a CI check, not by discipline. Credentials differ per
machine, so a leaked demo value compromises one laptop. Rotation is `make down && gen-secrets
--force`. Compromising one AppRole yields read access to exactly one path.

**Negative.** A `docker compose up` now depends on a generated directory; `make demo` and CI run
the generator first, but a hand-typed `docker compose up` in a fresh clone fails with a missing
file until `./scripts/gen-secrets.sh` is run.

**Migrating an existing deployment.** `init-databases.sh` runs only on an *empty* pgdata volume, so
a database created under the old literals keeps them while the services start reading newly
generated ones — every service then fails to authenticate. `scripts/rotate-db-passwords.sh` ALTERs
the roles to match the secret files without dropping data, and is the same command used for routine
rotation. A fresh environment needs nothing.

**Scope, stated honestly.** Only `identity-service` and `vault-service` read from Vault.
`payment-hub`, `notification-service` and `risk-ai-service` hold no credentials, so a Vault client
there would be decoration; the remaining Spring services keep the configtree path and can be moved
by adding one dependency and one overlay entry.

**Not production Vault.** Vault runs in dev mode: in-memory storage, unsealed at boot, a root token
that exists at all. A production deployment needs file or Raft storage, a real unseal ceremony
(`docs/runbooks/key-ceremony.md`), and service identity from platform attestation — Kubernetes auth
or the Nitro-style enclave attestation this platform already models — instead of an AppRole
`secret-id` handed over on a volume by a bootstrap container. That handover is this design's weakest
link and is recorded here rather than hidden.
