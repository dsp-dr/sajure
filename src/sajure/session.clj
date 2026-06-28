(ns sajure.session
  "§6 Sessions, memory & context management — mirrors session.scm / context.scm
  / compaction.scm.

  A session is a JSON-friendly STRING-KEYED map persisted under XDG data home:
    {\"name\" .. \"model\" .. \"created\" .. \"updated\" ..
     \"messages\" [ {\"role\" .. \"content\" .. \"tokens\" N} .. ]
     \"stats\" {\"total_tokens\" N \"input_tokens\" N \"output_tokens\" N
               \"request_count\" N \"tool_calls\" N}}
  String keys (not keywords) so json/write-str + json/read-str round-trip
  faithfully (the §6 save/load contract).

  Design: the transforms (create / add-message / compaction / threshold checks)
  are PURE functions of a session value; the bang wrappers thread them through
  the session-atom atom and do the file IO. That keeps compaction + thresholds
  directly property-testable without touching disk."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sajure.config :as config]
            [sajure.json :as json]))

;;; ---------------------------------------------------------------------------
;;; Token estimation
;;; ---------------------------------------------------------------------------

(defn estimate-tokens
  "Rough token estimate: ceil(chars/4), mirroring estimate-tokens in session.scm."
  [text]
  (if (string? text)
    (long (Math/ceil (/ (count text) 4.0)))
    0))

(defn message-tokens
  "Tokens for a message map: explicit \"tokens\" else estimate of \"content\"."
  [m]
  (or (get m "tokens") (estimate-tokens (or (get m "content") ""))))

;;; ---------------------------------------------------------------------------
;;; Pure session transforms
;;; ---------------------------------------------------------------------------

(def empty-stats
  {"total_tokens" 0 "input_tokens" 0 "output_tokens" 0
   "request_count" 0 "tool_calls" 0})

(defn new-session
  "Construct a fresh session value (pure)."
  [{:keys [name model]}]
  (let [ts (str (quot (System/currentTimeMillis) 1000))]
    {"name" (or name (str "session-" ts))
     "model" (or model (config/get-config "MODEL") "llama3.2:latest")
     "created" ts "updated" ts
     "messages" []
     "stats" empty-stats}))

(defn add-message
  "Append a message to SESSION (pure). Updates stats: total/in/out tokens,
  request_count (per assistant msg), tool_calls (when tool-call?)."
  [session role content & {:keys [tokens tool-call?]}]
  (let [tk (or tokens (estimate-tokens content))
        msg {"role" role "content" content
             "timestamp" (str (quot (System/currentTimeMillis) 1000))
             "tokens" tk}
        stats (get session "stats" empty-stats)
        stats' (-> stats
                   (update "total_tokens" + tk)
                   (cond-> (= role "user") (update "input_tokens" + tk))
                   (cond-> (= role "assistant") (update "output_tokens" + tk))
                   (cond-> (= role "assistant") (update "request_count" inc))
                   (cond-> tool-call? (update "tool_calls" inc)))]
    (-> session
        (update "messages" (fnil conj []) msg)
        (assoc "stats" stats')
        (assoc "updated" (str (quot (System/currentTimeMillis) 1000))))))

(defn total-tokens [session]
  (get-in session ["stats" "total_tokens"] 0))

(defn messages [session]
  (get session "messages" []))

(defn api-context
  "Project messages to the [{\"role\" \"content\"}] shape providers consume."
  [session]
  (mapv (fn [m] {"role" (get m "role") "content" (get m "content")})
        (messages session)))

(defn clear-messages
  "Reset messages + stats (the /reset semantics; keeps name/model)."
  [session]
  (-> session (assoc "messages" []) (assoc "stats" empty-stats)))

(defn status
  "Human-facing status map (string keys)."
  [session]
  (if session
    (let [s (get session "stats" empty-stats)]
      {"name" (get session "name") "model" (get session "model")
       "messages" (count (messages session))
       "total_tokens" (get s "total_tokens" 0)
       "input_tokens" (get s "input_tokens" 0)
       "output_tokens" (get s "output_tokens" 0)
       "requests" (get s "request_count" 0)
       "tool_calls" (get s "tool_calls" 0)})
    {"name" "none" "messages" 0 "total_tokens" 0}))

(defn format-status [session]
  (let [st (status session)]
    (format (str "Session: %s\nModel: %s\nMessages: %s\nTokens: %s (in: %s, out: %s)"
                 "\nRequests: %s\nTool calls: %s")
            (get st "name") (or (get st "model") "default")
            (get st "messages") (get st "total_tokens")
            (get st "input_tokens" 0) (get st "output_tokens" 0)
            (get st "requests" 0) (get st "tool_calls" 0))))

;;; ---------------------------------------------------------------------------
;;; §6 Context-window thresholds (75 / 90 / 95 %) — mirrors context.scm
;;; ---------------------------------------------------------------------------

(def thresholds
  "Descending [ratio level] pairs; each fires at most once per crossing."
  [[0.95 "critical"] [0.90 "high"] [0.75 "warning"]])

(defn usage-ratio
  "tokens/limit for SESSION under MODEL's limit (0 if no limit)."
  [session model]
  (let [limit (config/get-token-limit (or model (get session "model")))]
    (if (pos? limit) (/ (total-tokens session) limit) 0)))

(defn check-thresholds
  "Pure: given a usage RATIO and an already-FIRED set of level strings, return
  {:crossed [levels newly crossed] :fired updated-set}."
  [ratio fired]
  (reduce (fn [{:keys [crossed fired] :as acc} [tr level]]
            (if (and (>= ratio tr) (not (contains? fired level)))
              {:crossed (conj crossed level) :fired (conj fired level)}
              acc))
          {:crossed [] :fired (set fired)}
          thresholds))

(defn warning-line
  "User-facing warning string for a crossed LEVEL at PCT% (TOKENS/LIMIT)."
  [level pct tokens limit]
  (case level
    "critical" (format "[!] Context window %s%% full (%s/%s tokens). Context may be truncated. Run /compact now." pct tokens limit)
    "high"     (format "[!] Context window %s%% full (%s/%s tokens). Consider running /compact to free space." pct tokens limit)
    "warning"  (format "[*] Context window %s%% full (%s/%s tokens)." pct tokens limit)
    (format "[*] Context window at %s%% (%s/%s tokens)." pct tokens limit)))

;;; ---------------------------------------------------------------------------
;;; §6 Compaction strategies — mirrors compaction.scm
;;; ---------------------------------------------------------------------------

(defn- system? [m] (= "system" (get m "role")))

(defn compact-truncate
  "Keep system messages + the KEEP most recent non-system messages."
  ([msgs] (compact-truncate msgs 10))
  ([msgs keep]
   (if (<= (count msgs) keep)
     msgs
     (let [sys (filterv system? msgs)
           others (filterv (complement system?) msgs)
           n (max 0 (- keep (count sys)))]
       (vec (concat sys (take-last n others)))))))

(defn- select-within-budget
  "Take messages (most-recent-first order) while cumulative tokens <= budget,
  return in original order."
  [msgs-recent-first budget]
  (loop [ms msgs-recent-first used 0 acc []]
    (if (empty? ms)
      (reverse acc)
      (let [t (message-tokens (first ms))]
        (if (> (+ used t) budget)
          (reverse acc)
          (recur (rest ms) (+ used t) (conj acc (first ms))))))))

(defn compact-token-limit
  "Keep system messages + as many recent non-system messages as fit in
  MAX-TOKENS (system tokens reserved off the top)."
  ([msgs] (compact-token-limit msgs 4000))
  ([msgs max-tokens]
   (let [sys (filterv system? msgs)
         others (filterv (complement system?) msgs)
         budget (- max-tokens (reduce + 0 (map message-tokens sys)))]
     (vec (concat sys (select-within-budget (reverse others) budget))))))

(defn compact-summarize
  "Replace all but KEEP-RECENT messages with a single synthetic summary system
  message (deterministic summary, no LLM — mirrors generate-summary)."
  ([msgs] (compact-summarize msgs 5))
  ([msgs keep-recent]
   (let [n (count msgs)]
     (if (<= n keep-recent)
       msgs
       (let [to-sum (take (- n keep-recent) msgs)
             to-keep (take-last keep-recent msgs)
             users (count (filter #(= "user" (get % "role")) to-sum))
             summary (format "Previous conversation (%s messages): %s user requests."
                             (count to-sum) users)]
         (vec (cons {"role" "system"
                     "content" (str "[Context Summary]\n" summary)
                     "tokens" (estimate-tokens summary)
                     "compacted" true}
                    to-keep)))))))

(def strategies
  {"truncate" compact-truncate
   "token-limit" compact-token-limit
   "summarize" compact-summarize})

(defn compact-auto
  "Select a strategy by how far over TARGET-TOKENS the conversation is
  (mirrors compact-auto). Returns the compacted message vector."
  [msgs target-tokens]
  (let [cur (reduce + 0 (map message-tokens msgs))
        ratio (if (pos? target-tokens) (/ cur target-tokens) 0)]
    (cond
      (<= ratio 1.0) (vec msgs)
      (< ratio 1.5) (compact-truncate msgs 15)
      (< ratio 2.0) (compact-token-limit msgs target-tokens)
      :else (compact-summarize (compact-token-limit msgs target-tokens) 5))))

(defn recompute-stats
  "Recompute total_tokens from messages (after a compaction). Keeps the other
  counters (requests/tool_calls accrued historically)."
  [session]
  (let [tot (reduce + 0 (map message-tokens (messages session)))]
    (assoc-in session ["stats" "total_tokens"] tot)))

(defn maybe-compact
  "Pure auto-compaction trigger: if total tokens >= THRESHOLD-RATIO * limit,
  compact to 50% of the limit and return [new-session description]; else
  [session nil]. limit is resolved from the session model."
  ([session] (maybe-compact session 0.8))
  ([session threshold-ratio]
   (let [limit (config/get-token-limit (get session "model"))
         cur (total-tokens session)
         threshold (long (Math/floor (* limit threshold-ratio)))]
     (if (< cur threshold)
       [session nil]
       (let [target (long (Math/floor (* limit 0.5)))
             compacted (compact-auto (messages session) target)
             session' (-> session (assoc "messages" (vec compacted)) recompute-stats)
             new-tokens (total-tokens session')]
         [session' (format "Auto-compacted: %s -> %s tokens" cur new-tokens)])))))

;;; ---------------------------------------------------------------------------
;;; Persistence (XDG) — IO
;;; ---------------------------------------------------------------------------

(defn sessions-dir [] (config/sessions-dir))

(defn- ensure-dir! [d] (.mkdirs (io/file d)))

(defn session-file
  "Resolve a session NAME to a .json path. A name containing '/' or ending in
  .json is treated as a literal path; otherwise it lives under sessions-dir."
  [name]
  (cond
    (str/ends-with? name ".json") name
    (str/includes? name "/") (str name ".json")
    :else (str (sessions-dir) "/" name ".json")))

(defn save-session
  "Persist SESSION as JSON. Returns the file path."
  ([session] (save-session session (get session "name")))
  ([session name]
   (let [path (session-file name)]
     (ensure-dir! (sessions-dir))
     (spit path (json/write-str session))
     path)))

(defn load-session-file
  "Read + parse a session from NAME (path or bare name). Returns the session
  map, or nil if the file does not exist."
  [name]
  (let [f (io/file (session-file name))]
    (when (.exists f)
      (json/read-str (slurp f)))))

(defn list-sessions
  "Bare names (sans .json) of saved sessions under sessions-dir."
  []
  (let [d (io/file (sessions-dir))]
    (if (.isDirectory d)
      (->> (.listFiles d)
           (map #(.getName %))
           (filter #(str/ends-with? % ".json"))
           (mapv #(subs % 0 (- (count %) 5))))
      [])))

;;; ---------------------------------------------------------------------------
;;; Stateful session atom (REPL live conversation)
;;; ---------------------------------------------------------------------------

(defonce ^{:doc "Live session value (or nil). Mirrors session.scm session-atom."}
  session-atom (atom nil))

(defonce ^{:doc "Set of threshold levels already fired (reset on compaction)."}
  fired-atom (atom #{}))

(defn create! [opts] (reset! fired-atom #{}) (reset! session-atom (new-session opts)))

(defn add-message! [role content & opts]
  (when (nil? @session-atom) (create! {}))
  (swap! session-atom #(apply add-message % role content opts)))

(defn reset-conversation!
  "/reset: clear the live conversation (keeps name/model)."
  []
  (reset! fired-atom #{})
  (swap! session-atom clear-messages)
  "Session cleared.")

(defn save! [& [name]]
  (when (nil? @session-atom) (throw (ex-info "No active session" {})))
  (str "Saved to " (if name (save-session @session-atom name) (save-session @session-atom))))

(defn load! [name]
  (if-let [s (load-session-file name)]
    (do (reset! fired-atom #{}) (reset! session-atom s)
        (format "Loaded %s (%s messages)" (get s "name") (count (messages s))))
    (format "Session not found: %s" name)))

(defn compact!
  "/compact [keep]: truncate to KEEP recent messages (default 10). Returns a
  description string."
  ([] (compact! 10))
  ([keep]
   (when (nil? @session-atom) (throw (ex-info "No active session" {})))
   (let [before (count (messages @session-atom))]
     (swap! session-atom (fn [s] (-> s (assoc "messages" (compact-truncate (messages s) keep))
                                  recompute-stats)))
     (reset! fired-atom #{})
     (format "Compacted: %s -> %s messages" before (count (messages @session-atom))))))

(defn context-warnings!
  "Check thresholds against the live session, update fired-atom, and return a vector
  of user-facing warning strings for newly-crossed levels."
  [model]
  (let [s @session-atom]
    (if (nil? s)
      []
      (let [ratio (usage-ratio s model)
            {:keys [crossed fired]} (check-thresholds ratio @fired-atom)
            limit (config/get-token-limit (or model (get s "model")))
            tokens (total-tokens s)
            pct (long (Math/round (* 100.0 (double ratio))))]
        (reset! fired-atom fired)
        (mapv #(warning-line % pct tokens limit) crossed)))))
