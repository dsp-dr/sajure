# sajure — GNU Makefile (use gmake; BSD make mis-parses this).
# Clojure 1.12 CLI (clj). JSON is hand-rolled; test.check via deps.edn :test.

CLJ ?= clj

.DEFAULT_GOAL := help

.PHONY: help build check run print mcp-server clean rename lsp-clean

help: ## Show this help
	@awk 'BEGIN{FS=":.*##"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

rename: ## Project-wide ns rename: gmake rename FROM=sajure.old TO=sajure.new
	$(CLJ) -M:refactor -m clojure-lsp.main rename --from "$(FROM)" --to "$(TO)"

lsp-clean: ## Tidy ns :require forms project-wide (clojure-lsp clean-ns)
	$(CLJ) -M:refactor -m clojure-lsp.main clean-ns

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
