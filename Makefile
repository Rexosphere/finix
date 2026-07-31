.PHONY: help verify test demo up up-pull down logs seed dist

COMPOSE := docker compose -f infra/compose/docker-compose.yml
export COMPOSE_PROJECT_NAME ?= finix
export FINIX_IMAGE_PREFIX ?= ghcr.io/rexosphere/finix
export FINIX_IMAGE_TAG ?= latest

help: ## Show targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-14s %s\n", $$1, $$2}'

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

demo: ## Build, start, wait healthy, seed, print URLs
	./scripts/up.sh

dist: ## Build submission zip
	./scripts/dist.sh

