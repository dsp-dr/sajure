# CLAUDE.md — sajure-v2 continuation guide

Cold-start guide for an agent picking up the **second reference implementation**
of sage (feature parity with guile-sage). Read this + `spec.org` before editing.

## 1. Build / test / run (runtime: Clojure CLI 1.12, `clj`; JDK + `curl` on PATH)

Use **gmake** (GNU make; default goal is `help`). All targets shell out to `clj`.

```sh
gmake build         # load every namespace (compile check) -> "build ok"
gmake check         # full test suite via custom runner; EXITS NONZERO on any fail
gmake run           # interactive agent REPL (needs a provider via env)
gmake print P="..." # one-shot prompt (-p/--print)
gmake mcp-server    # serve tool registry over stdio newline-JSON-RPC 2.0
```

Direct invocations:
```sh
clj -M:test                                    # tests (runner -> System/exit)
clj -M -m sajure.repl                     # REPL
clj -M -m sajure.repl -p "prompt"         # one-shot
clj -M -m sajure.repl mcp-server          # MCP server subcommand
clj -M:mcp-server                               # MCP server (alias)
```

Deps: only `org.clojure/test.check` (in `deps.edn` `:test` alias). JSON is
hand-rolled (`src/sajure/json.clj`) — do **not** add `clojure.data.json`.
Test runner = `test/sajure/runner.clj`; add new test namespaces there
(both the `:require` and `test-namespaces`) or they won't run.

Current state: **65 tests / 274 assertions, 0 failures**. Property generators run
200–500 trials. CI must stay green — the runner exits nonzero on any fail/error
(no masked greens).

## 2. Implemented vs partial, mapped to spec §

All namespaces are implemented (no stubs left). Authoritative spec where it
differs from local `spec.org` = **guile-sage/spec.org §1–§11 + src/sage/*.scm**.

| § | Area | Namespace | Status |
|---|------|-----------|--------|
| 7 | Taint boundary | `taint.clj` | done — guard→wrap `<tool-result>` CDATA, `]]>`→`]]]]><![CDATA[>`, byte-faithful, trust lattice |
| 3 | Permission + path | `tools.clj` | done — TOTAL **per-token** `safe-path?`, safe/unsafe + YOLO, immutable registry |
| 2 | Provider error norm | `providers.clj` | done — `[<Provider> <label>: <msg>]`, control-byte + **byte**-bounded |
| 2 | Provider chat/stream/models | `providers.clj` | done (ollama/gemini/openai); **openai+gemini streaming = partial** (non-stream fallback) |
| 8 | HTTP resilience | `http.clj` | done — curl, retry 408/429/5xx, code 0 fast-fail |
| 4 | MCP server | `mcp_server.clj` | done — stdio JSON-RPC, safe-only, -32700, **name-free no-oracle**, served-plain |
| 4 | MCP client | `mcp_client.clj` | done over **stdio**; **SSE transport = TODO** (discovered, not dialed) |
| 5 | Agent loop + slash cmds | `repl.clj` | done — tool-call loop, taint-wrap, caps, `-p` one-shot |
| 6 | Sessions + context + compaction | `session.clj` | done — XDG JSON, 75/90/95 thresholds, truncate/token-limit/summarize/auto |
| 11 | Config + token limits | `config.clj` | done — precedence, XDG, `get-token-limit` |

**Honestly-marked partial work** (search for `TODO(spec`):
- `mcp_client.clj` `discover-and-register!` filters to `:stdio`; SSE servers are
  discovered + surfaced but not connected (guile uses curl+FIFO SSE — not ported).
- `providers.clj` `chat-streaming`: only ollama does true NDJSON delta streaming;
  openai/gemini emit the whole content once.

## 3. Gotchas hit (save the next agent the rediscovery)

- **gmake, not make**; **clj, not lein/boot**. Working dir resets between bash
  calls in this harness — use absolute paths.
- The repo lives at `…/sajure-v2` (hyphen). Namespaces use `sajure.*`;
  files use `sajure/*.clj` (underscore — Clojure munging).
- `clojure.core/reset!` collision: an atom-mutating session fn named `reset!`
  shadows core and breaks every later `(reset! atom …)`. The stateful clear is
  `session/reset-conversation!`. Don't reintroduce a `reset!` defn.
- Earmuffs (`*foo*`) on a non-dynamic `def` emits a warning; the session atoms
  are `session-atom` / `fired-atom` to avoid it.
- Pure-vs-IO split is deliberate: `session` transforms (`add-message`,
  `compact-*`, `check-thresholds`, `maybe-compact`) are pure of a session value;
  bang wrappers thread the atom. `repl/run-turn` takes an injected `chat-fn` so
  the agent loop is tested with a mock — **never** call a live LLM in a test.
- Tool exec args are **string-keyed** maps (e.g. `(get args "text")`); provider
  tool-call arguments are parsed JSON (string keys). Keep that — keywordizing
  breaks tool execution. OpenAI sends tool args as a JSON *string* → re-parse.
- The MCP **server** serves PLAIN content; the agent **loop** taint-wraps. Don't
  cross those (a peer client owns its own trust boundary, §4/§7).

## 4. Spec ambiguities found (fed back as hardening)

- **JSON null vs `{}` vs absent = 3 distinct states.** `json/null` is a sentinel,
  not Clojure `nil`. `(get m k)`→`nil` = absent; sentinel = present-and-null. This
  drives MCP wire semantics: `id: null` demands a reply, *absent* `id` is a
  notification.
- **Path block is per-token, not substring/exact.** `.env` is a prefix rule
  (`.env` or `.env.*`); `.git`/`.ssh`/`.gnupg` are exact. Plain-segment match
  (the earlier port) MISSED `.env.local`. Block applies to `/tmp` too.
- **No-oracle needs a name-free constant.** Echoing the caller's tool name makes
  gated vs nonexistent replies differ per call (Eq violation) → fixed message
  `"Unknown tool"`; name to stderr only.
- **Error excerpt is byte-bounded + all-control-byte-stripped**, not char-bounded
  / whitespace-only; truncation must not split a UTF-8 code point.
- **Agent-loop shape** (boundary-only ports never exercised this): provider is
  called once per round (initial + after each tool batch); tool results re-enter
  as `user` messages inside the taint envelope; degenerate detection keys on tool
  *name + args* (legit paging with changing args is not a false stop).
- **Compaction thresholds vs auto-compact are separate**: 75/90/95 % warnings
  fire once per crossing (a fired set); auto-compact triggers at 0.8·limit and
  compacts to ~0.5·limit. `get-token-limit` = model-substring lookup, first match
  wins (order specifics first).
- **MCP-client discovery** splits `~/.claude.json` `mcpServers` into stdio
  (`command`) and SSE (`url`); only stdio is wired here.

## Conventions
Pure handlers where possible; immutable data; string keys for JSON-faithful maps,
keyword keys for internal records. Keep tests in the runner. **Local only — do
not push.**


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:970c3bf2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->


---

## Build-team continuation guide

# CLAUDE.md — sajure-v2 continuation guide

Cold-start guide for an agent picking up the **second reference implementation**
of sage (feature parity with guile-sage). Read this + `spec.org` before editing.

## 1. Build / test / run (runtime: Clojure CLI 1.12, `clj`; JDK + `curl` on PATH)

Use **gmake** (GNU make; default goal is `help`). All targets shell out to `clj`.

```sh
gmake build         # load every namespace (compile check) -> "build ok"
gmake check         # full test suite via custom runner; EXITS NONZERO on any fail
gmake run           # interactive agent REPL (needs a provider via env)
gmake print P="..." # one-shot prompt (-p/--print)
gmake mcp-server    # serve tool registry over stdio newline-JSON-RPC 2.0
```

Direct invocations:
```sh
clj -M:test                                    # tests (runner -> System/exit)
clj -M -m sajure.repl                     # REPL
clj -M -m sajure.repl -p "prompt"         # one-shot
clj -M -m sajure.repl mcp-server          # MCP server subcommand
clj -M:mcp-server                               # MCP server (alias)
```

Deps: only `org.clojure/test.check` (in `deps.edn` `:test` alias). JSON is
hand-rolled (`src/sajure/json.clj`) — do **not** add `clojure.data.json`.
Test runner = `test/sajure/runner.clj`; add new test namespaces there
(both the `:require` and `test-namespaces`) or they won't run.

Current state: **65 tests / 274 assertions, 0 failures**. Property generators run
200–500 trials. CI must stay green — the runner exits nonzero on any fail/error
(no masked greens).

## 2. Implemented vs partial, mapped to spec §

All namespaces are implemented (no stubs left). Authoritative spec where it
differs from local `spec.org` = **guile-sage/spec.org §1–§11 + src/sage/*.scm**.

| § | Area | Namespace | Status |
|---|------|-----------|--------|
| 7 | Taint boundary | `taint.clj` | done — guard→wrap `<tool-result>` CDATA, `]]>`→`]]]]><![CDATA[>`, byte-faithful, trust lattice |
| 3 | Permission + path | `tools.clj` | done — TOTAL **per-token** `safe-path?`, safe/unsafe + YOLO, immutable registry |
| 2 | Provider error norm | `providers.clj` | done — `[<Provider> <label>: <msg>]`, control-byte + **byte**-bounded |
| 2 | Provider chat/stream/models | `providers.clj` | done (ollama/gemini/openai); **openai+gemini streaming = partial** (non-stream fallback) |
| 8 | HTTP resilience | `http.clj` | done — curl, retry 408/429/5xx, code 0 fast-fail |
| 4 | MCP server | `mcp_server.clj` | done — stdio JSON-RPC, safe-only, -32700, **name-free no-oracle**, served-plain |
| 4 | MCP client | `mcp_client.clj` | done over **stdio**; **SSE transport = TODO** (discovered, not dialed) |
| 5 | Agent loop + slash cmds | `repl.clj` | done — tool-call loop, taint-wrap, caps, `-p` one-shot |
| 6 | Sessions + context + compaction | `session.clj` | done — XDG JSON, 75/90/95 thresholds, truncate/token-limit/summarize/auto |
| 11 | Config + token limits | `config.clj` | done — precedence, XDG, `get-token-limit` |

**Honestly-marked partial work** (search for `TODO(spec`):
- `mcp_client.clj` `discover-and-register!` filters to `:stdio`; SSE servers are
  discovered + surfaced but not connected (guile uses curl+FIFO SSE — not ported).
- `providers.clj` `chat-streaming`: only ollama does true NDJSON delta streaming;
  openai/gemini emit the whole content once.

## 3. Gotchas hit (save the next agent the rediscovery)

- **gmake, not make**; **clj, not lein/boot**. Working dir resets between bash
  calls in this harness — use absolute paths.
- The repo lives at `…/sajure-v2` (hyphen). Namespaces use `sajure.*`;
  files use `sajure/*.clj` (underscore — Clojure munging).
- `clojure.core/reset!` collision: an atom-mutating session fn named `reset!`
  shadows core and breaks every later `(reset! atom …)`. The stateful clear is
  `session/reset-conversation!`. Don't reintroduce a `reset!` defn.
- Earmuffs (`*foo*`) on a non-dynamic `def` emits a warning; the session atoms
  are `session-atom` / `fired-atom` to avoid it.
- Pure-vs-IO split is deliberate: `session` transforms (`add-message`,
  `compact-*`, `check-thresholds`, `maybe-compact`) are pure of a session value;
  bang wrappers thread the atom. `repl/run-turn` takes an injected `chat-fn` so
  the agent loop is tested with a mock — **never** call a live LLM in a test.
- Tool exec args are **string-keyed** maps (e.g. `(get args "text")`); provider
  tool-call arguments are parsed JSON (string keys). Keep that — keywordizing
  breaks tool execution. OpenAI sends tool args as a JSON *string* → re-parse.
- The MCP **server** serves PLAIN content; the agent **loop** taint-wraps. Don't
  cross those (a peer client owns its own trust boundary, §4/§7).

## 4. Spec ambiguities found (fed back as hardening)

- **JSON null vs `{}` vs absent = 3 distinct states.** `json/null` is a sentinel,
  not Clojure `nil`. `(get m k)`→`nil` = absent; sentinel = present-and-null. This
  drives MCP wire semantics: `id: null` demands a reply, *absent* `id` is a
  notification.
- **Path block is per-token, not substring/exact.** `.env` is a prefix rule
  (`.env` or `.env.*`); `.git`/`.ssh`/`.gnupg` are exact. Plain-segment match
  (the earlier port) MISSED `.env.local`. Block applies to `/tmp` too.
- **No-oracle needs a name-free constant.** Echoing the caller's tool name makes
  gated vs nonexistent replies differ per call (Eq violation) → fixed message
  `"Unknown tool"`; name to stderr only.
- **Error excerpt is byte-bounded + all-control-byte-stripped**, not char-bounded
  / whitespace-only; truncation must not split a UTF-8 code point.
- **Agent-loop shape** (boundary-only ports never exercised this): provider is
  called once per round (initial + after each tool batch); tool results re-enter
  as `user` messages inside the taint envelope; degenerate detection keys on tool
  *name + args* (legit paging with changing args is not a false stop).
- **Compaction thresholds vs auto-compact are separate**: 75/90/95 % warnings
  fire once per crossing (a fired set); auto-compact triggers at 0.8·limit and
  compacts to ~0.5·limit. `get-token-limit` = model-substring lookup, first match
  wins (order specifics first).
- **MCP-client discovery** splits `~/.claude.json` `mcpServers` into stdio
  (`command`) and SSE (`url`); only stdio is wired here.

## Conventions
Pure handlers where possible; immutable data; string keys for JSON-faithful maps,
keyword keys for internal records. Keep tests in the runner. **Local only — do
not push.**
