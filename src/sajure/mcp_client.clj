(ns sajure.mcp-client
  "§4 MCP CLIENT — mirrors mcp.scm.

  Discovers MCP servers from ~/.claude.json, performs the JSON-RPC handshake,
  lists their tools, registers them into a sage tool registry (default UNSAFE),
  and calls them. When a discovered tool's result is fed onto sage's OWN-LLM
  path it MUST pass through the §7 taint envelope — that wrapping happens in
  repl/execute-one-tool, so a registered tool's :exec returns PLAIN text here.

  Transport: stdio newline-delimited JSON-RPC 2.0 is implemented (the same wire
  our own mcp-server speaks, so client+server interoperate). SSE servers are
  DISCOVERED and surfaced, but the SSE transport itself is a spec-tagged TODO
  (guile-sage uses curl+FIFO SSE; not ported).

  The pure parts — discovery parsing (extract-servers), request framing
  (rpc-request), and tool registration (register-tools, which takes an injected
  call-fn) — are split from the stdio IO so they are testable without a live
  server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sajure.json :as json]
            [sajure.tools :as tools]))

;;; ---------------------------------------------------------------------------
;;; Discovery (pure)
;;; ---------------------------------------------------------------------------

(defn read-claude-json
  "Parse ~/.claude.json; return the map or nil on any failure."
  ([] (read-claude-json (str (or (System/getenv "HOME")
                                 (System/getProperty "user.home"))
                             "/.claude.json")))
  ([path]
   (let [f (io/file path)]
     (when (.exists f)
       (try (json/read-str (slurp f)) (catch Exception _ nil))))))

(defn extract-servers
  "From a parsed ~/.claude.json CONFIG, return a vector of server specs:
    {:name s :transport :stdio :command c :args [..]}   for command servers
    {:name s :transport :sse   :url u :headers {..}}     for url/SSE servers
  Mirrors mcp-extract-sse-servers but keeps stdio too (the implemented wire)."
  [config]
  (let [servers (and (map? config) (get config "mcpServers"))]
    (if-not (map? servers)
      []
      (vec
       (keep
        (fn [[name spec]]
          (when (map? spec)
            (let [url (get spec "url")
                  command (get spec "command")
                  stype (get spec "type")
                  headers (let [h (get spec "headers")] (if (map? h) h {}))
                  args (let [a (get spec "args")] (if (vector? a) a []))]
              (cond
                (string? command) {:name name :transport :stdio
                                   :command command :args args}
                (and (string? url) (or (= stype "sse") (nil? command)))
                {:name name :transport :sse :url url :headers headers}
                :else nil))))
        servers)))))

(defn discover
  "Discover MCP servers from ~/.claude.json (safe: [] on any failure)."
  ([] (discover (read-claude-json)))
  ([config] (try (extract-servers config) (catch Exception _ []))))

;;; ---------------------------------------------------------------------------
;;; JSON-RPC framing (pure)
;;; ---------------------------------------------------------------------------

(def ^:private id-counter (atom 0))

(defn rpc-request
  "Build a JSON-RPC request. Returns {:id n :line json-string}."
  [method params]
  (let [id (swap! id-counter inc)]
    {:id id
     :line (json/write-str {"jsonrpc" "2.0" "id" id
                            "method" method "params" (or params {})})}))

(defn rpc-notification [method params]
  (json/write-str {"jsonrpc" "2.0" "method" method "params" (or params {})}))

(defn extract-tool-text
  "Pull the concatenated text out of a tools/call RESULT envelope
  ({\"content\":[{\"type\":\"text\",\"text\":..}]}); falls back to pr-str."
  [result]
  (let [content (get result "content")]
    (if (vector? content)
      (str/join "" (keep #(get % "text") content))
      (pr-str result))))

;;; ---------------------------------------------------------------------------
;;; stdio transport (IO)
;;; ---------------------------------------------------------------------------

(defn open-stdio
  "Spawn a stdio MCP server (command + args). Returns {:proc :writer :reader}."
  [command args]
  (let [pb (ProcessBuilder. ^java.util.List (into [command] args))
        _ (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
        proc (.start pb)]
    {:proc proc
     :writer (io/writer (.getOutputStream proc))
     :reader (io/reader (.getInputStream proc))}))

(defn- send-line! [{:keys [writer]} line]
  (.write ^java.io.Writer writer (str line "\n"))
  (.flush ^java.io.Writer writer))

(defn- read-reply
  "Read lines until one parses to a response with id = TARGET-ID. Blank lines
  are framing (skipped). Returns the parsed map, or nil on EOF."
  [{:keys [reader]} target-id]
  (loop []
    (let [line (.readLine ^java.io.BufferedReader reader)]
      (cond
        (nil? line) nil
        (str/blank? line) (recur)
        :else (let [m (try (json/read-str line) (catch Exception _ nil))]
                (if (and (map? m) (= (get m "id") target-id)) m (recur)))))))

(defn- close-conn [{:keys [proc writer reader]}]
  (try (.close ^java.io.Writer writer) (catch Exception _ nil))
  (try (.close ^java.io.BufferedReader reader) (catch Exception _ nil))
  (try (.destroy ^Process proc) (catch Exception _ nil)))

(defn initialize!
  "Send initialize + notifications/initialized over CONN. Returns the parsed
  initialize result (or nil)."
  [conn]
  (let [{:keys [id line]} (rpc-request "initialize"
                                       {"protocolVersion" "2025-06-18"
                                        "capabilities" {}
                                        "clientInfo" {"name" "sajure" "version" "v2"}})]
    (send-line! conn line)
    (let [reply (read-reply conn id)]
      (send-line! conn (rpc-notification "notifications/initialized" {}))
      (get reply "result"))))

(defn list-tools!
  "tools/list over CONN -> vector of tool descriptor maps."
  [conn]
  (let [{:keys [id line]} (rpc-request "tools/list" {})]
    (send-line! conn line)
    (let [res (get (read-reply conn id) "result")
          ts (get res "tools")]
      (if (vector? ts) ts []))))

(defn call-tool!
  "tools/call NAME with ARGS over CONN -> result text string."
  [conn name args]
  (let [{:keys [id line]} (rpc-request "tools/call" {"name" name "arguments" (or args {})})]
    (send-line! conn line)
    (let [reply (read-reply conn id)]
      (if-let [err (get reply "error")]
        (str "[error] MCP: " (get err "message"))
        (extract-tool-text (get reply "result"))))))

;;; ---------------------------------------------------------------------------
;;; Registration (pure — call-fn injected)
;;; ---------------------------------------------------------------------------

(defn register-tools
  "Turn DESCRIPTORS (tools/list output) into sage tool maps, namespaced
  `<server>.<tool>`, each :safe FALSE (MCP tools default unsafe, §4). Each
  :exec calls (CALL-FN tool-name args) and returns its PLAIN text result (the
  taint wrap happens later on the own-LLM path). Returns a vector of tool maps."
  [server-name descriptors call-fn]
  (mapv (fn [d]
          (let [tname (get d "name")
                desc (or (get d "description") (str "MCP tool: " tname))
                schema (or (get d "inputSchema")
                           {"type" "object" "properties" {}})]
            (tools/make-tool (str server-name "." tname)
                             (str "[" server-name "] " desc)
                             schema
                             (fn [args] (call-fn tname args))
                             :safe false)))
        descriptors))

(defn connect-and-register!
  "Full stdio flow for one command SERVER spec: open, initialize, list tools,
  and return a vector of registered tool maps whose :exec opens a fresh
  connection per call (mirrors mcp.scm's per-call SSE lifecycle). Returns [] on
  any failure (fail-soft, never throws)."
  [{:keys [name command args]}]
  (try
    (let [conn (open-stdio command args)]
      (initialize! conn)
      (let [descriptors (list-tools! conn)]
        (close-conn conn)
        (register-tools name descriptors
                        (fn [tname targs]
                          (let [c (open-stdio command args)]
                            (try (initialize! c) (call-tool! c tname targs)
                                 (finally (close-conn c))))))))
    (catch Throwable t
      (binding [*out* *err*]
        (println (str "[mcp-client] " name " registration failed: " (.getMessage t))))
      [])))

(defn discover-and-register!
  "Discover all stdio servers and register their tools. SSE servers are listed
  but skipped (transport TODO). Returns a flat vector of registered tool maps."
  []
  (->> (discover)
       (filter #(= :stdio (:transport %)))
       (mapcat connect-and-register!)
       vec))
