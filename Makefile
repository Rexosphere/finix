.PHONY: help verify test demo demo-pull up up-pull down logs seed rebuild dist monitoring \
	monitoring-down secrets vault-demo scale backup restore provision provision-check

COMPOSE := docker compose -f infra/compose/docker-compose.yml
COMPOSE_VAULT := docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.vault.yml
export COMPOSE_PROJECT_NAME ?= finix
export FINIX_IMAGE_PREFIX ?= ghcr.io/rexosphere/finix
export FINIX_IMAGE_TAG ?= latest

help: ## Show targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-16s %s\n", $$1, $$2}'

verify: ## Full local gate (compile + unit + ArchUnit + coverage + detekt)
	./gradlew verify

test: ## Unit tests only
	./gradlew test

integrationTest: ## Testcontainers suites (requires Docker)
	./gradlew integrationTest

hooks: ## Install the repo's git hooks (fast staged-file checks)
	git config core.hooksPath .githooks
	@echo "hooks installed: .githooks/pre-commit"

secrets: ## Generate the local credential files compose mounts as Docker secrets
	./scripts/gen-secrets.sh --show

up: secrets ## Start core compose profile (local image build)
	$(COMPOSE) --profile core up -d --build

up-pull: secrets ## Start core profile from published GHCR images (no local build)
	$(COMPOSE) --profile core pull
	$(COMPOSE) --profile core up -d --no-build

down: ## Stop compose stack
	$(COMPOSE) --profile core down -v

logs: ## Tail compose logs
	$(COMPOSE) --profile core logs -f --tail=200

seed: ## Seed personas into running stack
	./scripts/seed.sh

vault-demo: secrets ## Start the full profile with credentials served from Vault (ADR-0006)
	$(COMPOSE_VAULT) --profile full up -d --build
	@echo "identity-service and vault-service now read their DB password from Vault:"
	@echo "  docker compose -f infra/compose/docker-compose.yml --profile full logs vault-bootstrap"

monitoring: secrets ## Start Prometheus + Grafana + Loki + Tempo + Alertmanager (Grafana on :3002)
	$(COMPOSE) --profile monitoring up -d
	@echo "Grafana   http://localhost:3002  (admin / $$(cat infra/compose/secrets/grafana_admin_password))"
	@echo
	@echo "Traces are only exported when sampling is on. To see a transfer cross"
	@echo "every service, restart the app services with sampling enabled:"
	@echo "  FINIX_TRACE_SAMPLE=1.0 $(COMPOSE) --profile core up -d"

monitoring-down: ## Stop the monitoring profile, keeping the core stack up
	$(COMPOSE) --profile monitoring down

demo: ## Build locally, start, wait healthy, seed, print URLs
	./scripts/up.sh

demo-pull: ## Pull GHCR images, start, wait healthy, seed, print URLs (Docker only)
	./scripts/up-pull.sh

rebuild: ## Rebuild + recreate one service locally: make rebuild SERVICE=identity-service
	@test -n "$(SERVICE)" || (echo 'Usage: make rebuild SERVICE=<name>' >&2; exit 1)
	@if [ -f "services/$(SERVICE)/build.gradle.kts" ]; then \
	  ./gradlew :$(SERVICE):bootJar; \
	fi
	$(COMPOSE) --profile core build $(SERVICE)
	$(COMPOSE) --profile core up -d --no-deps $(SERVICE)

provision: ## Converge the production host (idempotent; needs SSH access)
	cd infra/ansible && ansible-playbook -i inventory/production.yml site.yml

provision-check: ## Dry-run the provisioning playbook (changes nothing)
	cd infra/ansible && ansible-playbook -i inventory/production.yml site.yml --check --diff

scale: ## Scale a stateless service: make scale SERVICE=payment-hub N=3
	@test -n "$(SERVICE)" || (echo 'Usage: make scale SERVICE=<payment-hub|notification-service|risk-ai-service> N=<count>' >&2; exit 1)
	@test -n "$(N)" || (echo 'Usage: make scale SERVICE=<name> N=<count>' >&2; exit 1)
	docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.scale.yml \
	  --profile core up -d --no-deps --scale $(SERVICE)=$(N) $(SERVICE)
	@echo "Replicas now serving behind nginx (upstreams resolve per request):"
	docker compose -f infra/compose/docker-compose.yml --profile core ps $(SERVICE)

backup: ## Take a Postgres dump now (same script the nightly timer runs)
	/opt/finix/backup-db.sh

restore: ## Restore the latest dump (interactive, destructive)
	./scripts/restore-db.sh --latest

dist: ## Build submission zip
	./scripts/dist.sh

