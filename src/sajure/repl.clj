(ns sajure.repl
  "§5 Agent loop & REPL — mirrors repl.scm.

  The interactive loop: read prompt -> provider chat (streaming or not) ->
  tool-call loop (execute each tool via the registry, feed results back through
  the §7 taint envelope on the OWN-LLM path, re-prompt to a cap) -> final
  answer. Plus slash commands matching guile-sage.

  Boundary: this is sage's OWN-LLM path, so EVERY tool result is taint-wrapped
  (guard-tool-result -> wrap-tool-result) before it re-enters the conversation
  as a user message — exactly the single choke point the MCP *server* must NOT
  apply (§4).

  Testability: the tool-call loop (run-turn) takes the provider call as an
  injected CHAT-FN (model messages registry)->normalized-response, so the
  loop/wiring is tested with a mock provider — no live LLM. Slash-command
  dispatch (handle-command) is a pure-ish function returning a result map."
  (:require [clojure.string :as str]
            [sajure.attest :as attest]
            [sajure.config :as config]
            [sajure.providers :as providers]
            [sajure.session :as session]
            [sajure.taint :as taint]
            [sajure.tools :as tools]
            [sajure.mcp-client :as mcp-client]
            [sajure.version :as version])
  (:gen-class))

;;; ---------------------------------------------------------------------------
;;; Tool-call loop (§5) — the agent loop core
;;; ---------------------------------------------------------------------------

(def max-tool-iterations
  "Safety cap on re-prompt rounds (mirrors *max-tool-iterations*)." 10)

(def max-same-tool-repeats
  "Degenerate-loop cutoff: identical tool+args this many times in a row stops
  the chain (mirrors *max-same-tool-repeats*)." 3)

(defn execute-one-tool
  "Run ONE tool-call {:name :arguments} against REGISTRY under YOLO?. Returns
  the §7 taint-WRAPPED result string (own-LLM path). Unknown tool / permission
  denied / thrown exec all collapse to a guard-wrapped error — never throws.

  §17.4 (Path A, actor own-llm): a MUTATING tool emits exactly one attestation
  record — allow ⇒ result-sha over the PRE-taint-wrap `guarded` bytes (NOT the
  CDATA envelope, property #6); deny ⇒ no result-sha and the exec never ran
  (property #3). Safe (read-only) tools are exempt (property #10). The execution
  gate is unchanged (tools/allowed?, defense in depth); the verdict mirrors it,
  so threading attestation in changes no behavior."
  [registry yolo? {:keys [name arguments]}]
  (let [tool (tools/find-tool registry name)
        args (or arguments {})
        mutating? (and tool (not (:safe tool)))
        allowed? (and tool (tools/allowed? tool yolo?))
        raw (cond
              (nil? tool)
              (str "[error] Unknown tool: " name)
              (not allowed?)
              (str "[error] Permission denied: " name
                   " is unsafe (requires confirmation or SAGE_YOLO_MODE=1)")
              :else
              (try (let [r ((:exec tool) args)]
                     (if (string? r) r (str r)))
                   (catch Throwable t (str "[error] Tool error: " (.getMessage t)))))
        guarded (taint/guard-tool-result raw)]
    ;; §17.4: attest MUTATING tools only (safe tools exempt).
    (when mutating?
      (let [action (attest/canonical-action name args
                                            :actor "own-llm"
                                            :mutation-class "mutating"
                                            :safe-path? tools/safe-path?)
            verdict (attest/policy-verdict action {"YOLO_MODE" (if yolo? "1" "")})
            allow? (= :allow (attest/verdict-decision verdict))]
        (attest/attest! action verdict
                        :actor "own-llm"
                        :result-sha (when allow? (attest/sha256-hex guarded)))))
    (taint/wrap-tool-result name guarded (boolean (and tool (:safe tool))))))

(defn run-turn
  "Process one user turn against SESSION and return [session' final-text].
  CTX: {:registry :chat-fn :yolo? [:model] [:on-tool]}.
    :chat-fn  (model messages registry) -> normalized response map
              {:content :tool-calls [{:name :arguments}] :completion-tokens ..}
    :on-tool  optional (name wrapped-result) side-effect for display.
  The loop re-prompts after executing tools until the model stops requesting
  them, a degenerate repeat is detected, or the iteration cap is hit."
  [{:keys [registry chat-fn yolo? model on-tool]} session user-input]
  (let [session (session/add-message session "user" user-input)
        model (or model (get session "model"))]
    (loop [session session, iter 0, prev-sig nil, repeats 0]
      (let [resp (chat-fn model (session/api-context session) registry)
            content (or (:content resp) "")
            tcs (:tool-calls resp)
            ct (:completion-tokens resp)]
        (if (empty? tcs)
          [(session/add-message session "assistant" content :tokens ct) content]
          (let [first-tc (first tcs)
                sig (str (:name first-tc) ":" (pr-str (:arguments first-tc)))
                repeats' (if (= sig prev-sig) (inc repeats) 0)
                degenerate? (>= repeats' max-same-tool-repeats)]
            (if (or (>= iter max-tool-iterations) degenerate?)
              [(session/add-message session "assistant" content :tokens ct) content]
              (let [session (session/add-message session "assistant" content
                                                 :tokens ct :tool-call? true)
                    session (reduce
                             (fn [s tc]
                               (let [wrapped (execute-one-tool registry yolo? tc)]
                                 (when on-tool (on-tool (:name tc) wrapped))
                                 (session/add-message s "user" wrapped)))
                             session tcs)]
                (recur session (inc iter) sig repeats')))))))))

;;; ---------------------------------------------------------------------------
;;; Slash commands (§5)
;;; ---------------------------------------------------------------------------

(def streaming?
  "Streaming on unless SAGE_STREAMING=0 (mirrors *streaming*)."
  (atom (not= "0" (or (System/getenv "SAGE_STREAMING") ""))))

(def debug? (atom false))

(def help-text
  (str/join
   "\n"
   ["Commands:"
    "  /help            Show this help"
    "  /version         Show version"
    "  /tools           List registered tools (safe/unsafe)"
    "  /model [name]    Show or set the session model"
    "  /models          List provider models"
    "  /sessions        List saved sessions"
    "  /save [name]     Save the current session"
    "  /load <name>     Load a saved session"
    "  /reset           Clear the conversation"
    "  /clear           Alias for /reset"
    "  /stats           Show session stats"
    "  /context         Show context-window usage"
    "  /stream          Toggle streaming"
    "  /compact [n]     Compact to N recent messages (default 10)"
    "  /mcp             List discovered MCP servers"
    "  /debug           Toggle debug mode"
    "  /exit /quit      Exit"]))

(defn- tool-listing [registry]
  (str/join "\n"
            (cons (format "%d tools (%d safe):"
                          (count registry) (count (filter :safe registry)))
                  (map (fn [t] (format "  %-16s %s  %s"
                                       (:name t)
                                       (if (:safe t) "[safe]  " "[unsafe]")
                                       (:description t)))
                       registry))))

(defn handle-command
  "Dispatch a slash-command line. Returns
    {:handled? bool :session session' :output str :exit? bool}
  Pure for the state-only commands (help/version/tools/reset/compact/stats/
  context/model/stream/debug); save/load/sessions/models/mcp do IO."
  [{:keys [registry] :as ctx} session input]
  (let [parts (str/split (str/trim input) #"\s+")
        cmd (first parts)
        arg (when (> (count parts) 1) (str/join " " (rest parts)))
        ret (fn [m] (merge {:handled? true :session session :exit? false :output ""} m))]
    (case cmd
      "/help"     (ret {:output help-text})
      "/version"  (ret {:output (str "sajure " (version/version-string))})
      "/tools"    (ret {:output (tool-listing registry)})
      ("/exit" "/quit") (ret {:exit? true :output "Bye."})
      ("/reset" "/clear")
      (ret {:session (session/clear-messages session) :output "Session cleared."})
      "/stats"    (ret {:output (session/format-status session)})
      "/status"   (ret {:output (session/format-status session)})
      "/context"
      (let [ratio (session/usage-ratio session (get session "model"))
            limit (config/get-token-limit (get session "model"))
            pct (long (Math/round (* 100.0 (double ratio))))]
        (ret {:output (format "Context: %s/%s tokens (%s%%)"
                              (session/total-tokens session) limit pct)}))
      "/model"
      (if arg
        (ret {:session (assoc session "model" arg) :output (str "Model set to " arg)})
        (ret {:output (str "Model: " (get session "model"))}))
      "/stream"
      (do (swap! streaming? not)
          (ret {:output (str "Streaming " (if @streaming? "on" "off"))}))
      "/debug"
      (do (swap! debug? not)
          (ret {:output (str "Debug " (if @debug? "on" "off"))}))
      "/compact"
      (let [keep (or (when arg (try (Integer/parseInt arg) (catch Exception _ nil))) 10)
            before (count (session/messages session))
            session' (-> session
                         (assoc "messages" (session/compact-truncate
                                            (session/messages session) keep))
                         session/recompute-stats)]
        (ret {:session session'
              :output (format "Compacted: %s -> %s messages"
                              before (count (session/messages session')))}))
      "/sessions"
      (ret {:output (let [ss (session/list-sessions)]
                      (if (seq ss) (str/join "\n" ss) "No saved sessions."))})
      "/save"
      (ret {:output (str "Saved to "
                         (if arg (session/save-session session arg)
                             (session/save-session session)))})
      "/load"
      (if-let [s (and arg (session/load-session-file arg))]
        (ret {:session s :output (format "Loaded %s (%s messages)"
                                         (get s "name") (count (session/messages s)))})
        (ret {:output (str "Session not found: " arg)}))
      "/models"
      (ret {:output (let [ms (providers/list-models)]
                      (if (seq ms) (str/join "\n" ms) "No models (provider unreachable)."))})
      "/mcp"
      (ret {:output (let [servers (mcp-client/discover)]
                      (if (seq servers)
                        (str/join "\n" (map #(format "  %-16s %s"
                                                     (:name %) (or (:url %) (:command %)))
                                            servers))
                        "No MCP servers discovered (~/.claude.json)."))})
      ;; not a slash command we know
      (if (str/starts-with? (or cmd "") "/")
        (ret {:output (str "Unknown command: " cmd "\nType /help for commands.")})
        {:handled? false :session session :exit? false :output ""}))))

;;; ---------------------------------------------------------------------------
;;; IO shell — interactive loop + one-shot
;;; ---------------------------------------------------------------------------

(defn- default-ctx []
  (let [registry tools/default-registry]
    {:registry registry
     :yolo? (tools/yolo?)
     :chat-fn (fn [model messages registry] (providers/chat model messages registry))
     :on-tool (fn [name wrapped]
                (println (format "  [Tool: %s]" name))
                (when @debug? (println wrapped)))}))

(defn one-shot
  "Run a single prompt non-interactively (-p/--print). Prints the final answer.
  Uses a real provider; with no reachable LLM the answer is the normalized
  provider error-line (honest, not faked)."
  [prompt]
  (let [ctx (default-ctx)
        session (-> (session/new-session {:model (providers/provider-model (providers/current-provider))})
                    (session/add-message "system" taint/tool-result-system-prompt))
        [_ final] (run-turn ctx session prompt)]
    (println final)))

(defn run
  "Interactive REPL loop over stdin."
  [& _]
  (config/get-config "PROVIDER") ; touch config (loads dotenv)
  (let [ctx (default-ctx)
        provider (providers/current-provider)]
    (session/create! {:model (providers/provider-model provider)})
    (session/add-message! "system" taint/tool-result-system-prompt)
    (println (format "sajure %s — provider: %s, model: %s"
                     (version/version-string) (name provider)
                     (get @session/session-atom "model")))
    (println "Type /help for commands, /exit to quit.")
    (loop []
      (print (format "\n[%s] > " (get @session/session-atom "model")))
      (flush)
      (let [line (read-line)]
        (cond
          (nil? line) (println "\nBye.")
          (str/blank? line) (recur)
          (str/starts-with? line "/")
          (let [{:keys [session output exit?]} (handle-command ctx @session/session-atom line)]
            (when session (reset! session/session-atom session))
            (when (seq output) (println output))
            (if exit? nil (recur)))
          :else
          (let [[session' final] (run-turn (assoc ctx :chat-fn
                                                  (if @streaming?
                                                    (fn [m msgs reg]
                                                      (providers/chat-streaming m msgs reg print))
                                                    (:chat-fn ctx)))
                                           @session/session-atom line)]
            (reset! session/session-atom session')
            (println (str "\n" final))
            (doseq [w (session/context-warnings! (get session' "model"))]
              (println w))
            (recur)))))))

(defn -main
  "Entry: `mcp-server` subcommand serves the registry; -p/--print runs one-shot;
  otherwise the interactive REPL."
  [& args]
  (cond
    (= (first args) "mcp-server")
    ((requiring-resolve 'sajure.mcp-server/-main))
    (#{"-p" "--print"} (first args))
    (one-shot (str/join " " (rest args)))
    :else (run)))
