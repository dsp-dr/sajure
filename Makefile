# sajure-v2 — GNU Makefile (use gmake; BSD make mis-parses this).
# Clojure 1.12 CLI (clj). JSON is hand-rolled; test.check via deps.edn :test.

CLJ ?= clj

.DEFAULT_GOAL := help

.PHONY: help build check run print mcp-server clean

help: ## Show this help
	@awk 'BEGIN{FS=":.*##"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

build: ## AOT-free compile check: load every namespace
	$(CLJ) -M -e "(doseq [n '[sajure.version sajure.json sajure.config sajure.http sajure.providers sajure.tools sajure.taint sajure.mcp-server sajure.mcp-client sajure.repl sajure.session]] (require n)) (println \"build ok\")"

check: ## Run property + boundary + surface tests (nonzero exit on failure)
	$(CLJ) -M:test

run: ## Start the interactive agent REPL (needs a provider via env)
	$(CLJ) -M -m sajure.repl

print: ## One-shot: gmake print P="your prompt"
	$(CLJ) -M -m sajure.repl -p "$(P)"

mcp-server: ## Serve the tool registry over stdio JSON-RPC 2.0
	$(CLJ) -M:mcp-server

clean: ## Remove build caches
	rm -rf .cpcache target
