.PHONY: help verify test demo demo-pull up up-pull down logs seed rebuild dist monitoring monitoring-down

COMPOSE := docker compose -f infra/compose/docker-compose.yml
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

up: ## Start core compose profile (local image build)
	$(COMPOSE) --profile core up -d --build

up-pull: ## Start core profile from published GHCR images (no local build)
	$(COMPOSE) --profile core pull
	$(COMPOSE) --profile core up -d --no-build

down: ## Stop compose stack
	$(COMPOSE) --profile core down -v

logs: ## Tail compose logs
	$(COMPOSE) --profile core logs -f --tail=200

seed: ## Seed personas into running stack
	./scripts/seed.sh

monitoring: ## Start Prometheus + Grafana + Loki alongside the core stack (Grafana on :3002)
	$(COMPOSE) --profile monitoring up -d
	@echo "Grafana   http://localhost:3002  (admin / $${FINIX_GRAFANA_ADMIN_PASSWORD:-admin})"

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

dist: ## Build submission zip
	./scripts/dist.sh

