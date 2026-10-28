.DEFAULT_GOAL := help

CLOJURE ?= clojure
DOCKER ?= docker
ARGS ?=
BUMP ?= patch
VERSION ?=
RABBITMQ_URI ?= amqp://localhost:5672

.PHONY: help deps outdated update run repl demo demo-rabbitmq test test-integration lint check ci rabbitmq-up rabbitmq-down version release

help: ## List available targets
	@awk 'BEGIN {FS = ":.*## "; print "Usage: make <target> [ARGS=\"...\"]\n"} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

deps: ## Download application, test, lint, and dependency-update tools
	$(CLOJURE) -P -M:run
	$(CLOJURE) -P -M:test
	$(CLOJURE) -P -M:lint
	$(CLOJURE) -P -M:outdated

outdated: ## Report outdated dependencies in deps.edn
	$(CLOJURE) -M:outdated $(ARGS)

update: ## Upgrade dependencies in deps.edn, then run lint and tests
	$(CLOJURE) -M:outdated --upgrade --force $(ARGS)
	$(MAKE) check

run: ## Start a node (ARGS="processor --port 8081", etc.)
	$(CLOJURE) -M:run $(ARGS)

repl: ## Open an application REPL
	$(CLOJURE) -M -r $(ARGS)

demo: ## Run the guided Nile demo
	$(CLOJURE) -M:demo $(ARGS)

demo-rabbitmq: ## Run the demo over RabbitMQ (start the broker first)
	$(CLOJURE) -M:demo --transport rabbitmq $(ARGS)

test: ## Run tests (RabbitMQ integration runs when THEBES_RABBITMQ_URI is set)
	$(CLOJURE) -M:test $(ARGS)

test-integration: ## Run all tests against RabbitMQ (start the broker first)
	$(CLOJURE) -M:test $(ARGS)

lint: ## Lint source and tests with clj-kondo
	$(CLOJURE) -M:lint $(ARGS)

check: lint test ## Run lint and tests

ci: check ## Run checks and both plain demos (requires RabbitMQ)
	$(CLOJURE) -M:demo --plain --rate 720
	$(CLOJURE) -M:demo --plain --rate 720 --transport rabbitmq

ci test-integration demo-rabbitmq: export THEBES_RABBITMQ_URI = $(RABBITMQ_URI)

rabbitmq-up: ## Start the local RabbitMQ broker and wait for readiness
	$(DOCKER) compose up -d --wait rabbitmq

rabbitmq-down: ## Stop the local RabbitMQ broker
	$(DOCKER) compose stop rabbitmq

version: ## Show the latest vMAJOR.MINOR.PATCH tag (0.0.0 before the first release)
	@sh scripts/release.sh version

release: ## Check a clean checkout and tag a release (BUMP=patch|minor|major or VERSION=x.y.z)
	@sh scripts/release.sh release "$(BUMP)" "$(VERSION)"
