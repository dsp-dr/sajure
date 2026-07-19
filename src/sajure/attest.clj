(ns sajure.attest
  "§17 CAVA — Canonical Action Verification + Attestation (RFC-004).

  CAVA is 'formalize-and-thread': it reuses the existing mutation choke points
  (repl execute-one-tool = Path A own-LLM; mcp-server on-tools-call = Path B MCP
  peer), an append-only audit log, and a prev_sha hash-chain. This namespace
  defines the three first-class values as PURE/TOTAL constructors plus a
  best-effort append-only log:

    §17.1 canonical-action   — deterministic {tool,args,paths,mutation-class,actor,ts} + action-sha
    §17.2 policy-verdict     — pure (action,env) -> {decision, reason, inputs}, fail-closed
    §17.3 attestation        — {ts,actor,action-sha,decision,reason,result-sha?,prev-sha,gpg-sig?}
    §17.6 attest-log-append! — best-effort by default; SAGE_ATTEST_STRICT aborts on
                               write failure; SAGE_ATTEST_DISABLE opts out of LOGGING only.

  SHA-256 is java.security.MessageDigest (JDK stdlib — no new dep, native, no
  subprocess, no /tmp fork). Property-tested against the FIPS-180-4 vectors
  ('' / 'abc' / 'hello' / the multi-block 248d6a61… vector).

  This namespace intentionally does NOT require (sajure tools): the caller passes
  in the pure safe-path?/resolve-path predicates, so attest stays dependency-light
  and cycle-free."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

;;; ============================================================
;;; SHA-256 — java.security.MessageDigest (JDK stdlib, no new dep)
;;; ============================================================

(defn sha256-hex
  "SHA-256 hex digest of the UTF-8 encoding of STR. Pure; no subprocess, no
  /tmp fork (unlike a shasum-forking approach). nil hashes as the empty string."
  [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (str (or s "")) StandardCharsets/UTF_8))
        sb (StringBuilder. (* 2 (alength bytes)))]
    (doseq [b bytes]
      (.append sb (format "%02x" (bit-and (int b) 0xff))))
    (.toString sb)))

;;; ============================================================
;;; Canonical JSON (deterministic, key-sorted) — §16.3
;;; ============================================================
;;;
;;; Byte-canonical re-encode: object keys sorted, stable value encoding.
;;; Independent of (sajure json) so the attestation encoding never drifts with
;;; unrelated JSON changes. This is what makes action-sha deterministic
;;; (property #4) and arg-permutation invariant (property #5).

(defn- canon-escape [^String s]
  (let [sb (StringBuilder.)]
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (case c
          \" (.append sb "\\\"")
          \\ (.append sb "\\\\")
          \newline (.append sb "\\n")
          \return (.append sb "\\r")
          \tab (.append sb "\\t")
          (.append sb c))))
    (.toString sb)))

(defn- key-str [k] (if (string? k) k (name k)))

(defn canon-json
  "Deterministic JSON encoding of OBJ. Object (map) keys are sorted; arrays keep
  order. Handles nil (null), booleans, numbers, strings, keywords/symbols,
  string- or keyword-keyed maps, and vectors/seqs."
  [obj]
  (cond
    (nil? obj) "null"
    (true? obj) "true"
    (false? obj) "false"
    (string? obj) (str \" (canon-escape obj) \")
    (keyword? obj) (str \" (canon-escape (name obj)) \")
    (symbol? obj) (str \" (canon-escape (str obj)) \")
    (integer? obj) (str obj)
    (number? obj) (str obj)
    (map? obj)
    (let [parts (->> obj
                     (map (fn [[k v]] [(key-str k) v]))
                     (sort-by first)
                     (map (fn [[k v]]
                            (str \" (canon-escape k) "\":" (canon-json v)))))]
      (str "{" (str/join "," parts) "}"))
    (sequential? obj)
    (str "[" (str/join "," (map canon-json obj)) "]")
    :else (str \" (canon-escape (str obj)) \")))

;;; ============================================================
;;; §17.1 — Canonical action
;;; ============================================================
;;;
;;; Deterministic normalized record {tool, args, paths, mutation-class, actor,
;;; ts}. Pure/total; never mutates state; MUST NOT loosen safe-path or coercion —
;;; it CONSULTS the caller's pure safe-path?/resolve-path, it does not
;;; re-implement them. action-sha covers {tool,args,paths,mutation-class,actor}
;;; and deliberately EXCLUDES ts, so it is time-independent (#4) and
;;; permutation-invariant (#5).

(defn now-iso
  "Current instant as an ISO-8601 string."
  []
  (.toString (java.time.Instant/now)))

(defn- arg-path-values
  "Extract path-shaped argument values from ARGS: the \"path\" string plus each
  element of a \"files\" array. Total; ignores non-strings."
  [args]
  (let [p (get args "path")
        files (get args "files")
        file-list (cond
                    (sequential? files) files
                    (string? files) [files]
                    :else [])]
    (filterv string? (into [(when (string? p) p)] file-list))))

(defn canonical-action
  "Build the canonical action record for a (TOOL, ARGS) call. MUTATION-CLASS is
  one of \"read-only\", \"mutating\", \"unclassified\" (string or keyword).
  SAFE-PATH?/RESOLVE-PATH are the caller's PURE predicates (from sajure.tools);
  path verdicts are RECORDED, not recomputed loosely."
  [tool args & {:keys [actor ts mutation-class safe-path? resolve-path]
                :or {actor "own-llm" mutation-class "mutating"
                     safe-path? (constantly true) resolve-path identity}}]
  (let [now (or ts (now-iso))
        norm-args (if (map? args) args {})
        paths (mapv (fn [raw]
                      {"raw" raw
                       "resolved" (let [r (try (resolve-path raw)
                                               (catch Throwable _ raw))]
                                    (if (string? r) r raw))
                       "safe" (boolean (try (safe-path? raw)
                                            (catch Throwable _ false)))})
                    (arg-path-values norm-args))
        mc (if (keyword? mutation-class) (name mutation-class) (str mutation-class))
        actor-str (if (keyword? actor) (name actor) (str actor))
        tool-str (if (string? tool) tool (str tool))
        ;; action-sha covers tool/args/paths/mutation-class/actor, NOT ts.
        sha-body {"actor" actor-str "args" norm-args "mutation-class" mc
                  "paths" paths "tool" tool-str}
        sha (sha256-hex (canon-json sha-body))]
    {"tool" tool-str "args" norm-args "paths" paths
     "mutation-class" mc "actor" actor-str "ts" now "action-sha" sha}))

(defn action-ref [action k] (get action k))
(defn action-sha [action] (get action "action-sha"))

;;; ============================================================
;;; §17.2 — Policy verdict
;;; ============================================================
;;;
;;; Pure (action, env) -> {decision ∈ {:allow :confirm :deny}, reason, inputs}.
;;; inputs records EVERY gate consulted. Deterministic; FAIL-CLOSED (any gate
;;; error ⇒ deny); default-deny for unclassified mutations.
;;;
;;; ENV is a plain string->string map, passed as DATA to keep this pure. The
;;; authorization gate is generic: Path A reads "YOLO_MODE"; Path B reads
;;; "SAGE_MCP_EXPOSE_UNSAFE" (per RFC-004 F2 — Path B's gate is EXPOSURE, not
;;; YOLO — so a Path-B allow reason never misreads as 'permitted by YOLO_MODE').
;;; The decision otherwise MIRRORS sajure.tools/allowed? so threading CAVA
;;; changes no behavior.

(defn affirmative?
  "Value-gate (§15.3): true iff V (trimmed, lower-cased) is one of
  1/true/yes/on. Presence alone is NOT enough."
  [v]
  (boolean
   (and v (contains? #{"1" "true" "yes" "on"} (str/lower-case (str/trim (str v)))))))

(defn make-verdict [decision reason inputs]
  {"decision" (name decision) "reason" reason "inputs" inputs})

(defn verdict-decision [v]
  (let [d (get v "decision")]
    (cond (keyword? d) d
          (string? d) (keyword d)
          :else :deny)))

(defn verdict-reason [v] (or (get v "reason") ""))
(defn verdict-inputs [v] (or (get v "inputs") {}))

(defn policy-verdict
  "Produce the policy verdict for ACTION under ENV. FAIL-CLOSED on any error."
  [action env & {:keys [pre-veto auth-key auth-label]
                 :or {auth-key "YOLO_MODE" auth-label "YOLO_MODE"}}]
  (try
    (let [mc (get action "mutation-class")
          paths (or (get action "paths") [])
          authorized (affirmative? (get env auth-key))
          path-verdicts (mapv #(boolean (get % "safe")) paths)
          all-paths-safe (every? true? path-verdicts)
          inputs {"mutation-class" mc
                  "safe-set" (= mc "read-only")
                  "authorized" authorized
                  "auth-source" auth-label
                  "path-verdicts" path-verdicts
                  "all-paths-safe" all-paths-safe
                  "pre-veto" (boolean pre-veto)}]
      (cond
        ;; A PreToolUse veto (§9) is fail-closed regardless of class.
        pre-veto
        (make-verdict :deny (str "PreToolUse veto: " pre-veto) inputs)
        ;; read-only (safe-set) tools are always allowed.
        (= mc "read-only")
        (make-verdict :allow "safe tool (read-only)" inputs)
        ;; known mutation: gate on the value-gated authorization input.
        (= mc "mutating")
        (if authorized
          (make-verdict :allow (str "mutation permitted by " auth-label) inputs)
          (make-verdict :deny (str "mutation requires " auth-label " (value-gated)") inputs))
        ;; anything else is an unclassified mutation ⇒ default-deny.
        :else
        (make-verdict :deny "unclassified mutation (default-deny)" inputs)))
    (catch Throwable t
      ;; Any gate error ⇒ deny (fail-closed, property #12).
      (make-verdict :deny
                    (str "verdict gate error (fail-closed): " (.getMessage t))
                    {"error" (str t)}))))

;;; ============================================================
;;; §17.3 — Attestation record + tamper-evident chain
;;; ============================================================
;;;
;;; {ts, actor, action-sha, decision, reason, result-sha?, prev-sha, gpg-sig?}.
;;; Byte-canonical (canon-json, stable re-encode). result-sha present only on
;;; allow (nil ⇒ encoded as null). prev-sha chains the previous record:
;;; record[N].prev-sha == sha256(encode(record[N-1])).

(defn attest-genesis-sha
  "Chain root: 64 zero hex chars (no predecessor)."
  []
  (apply str (repeat 64 \0)))

(defn attestation
  "Construct an attestation record for ACTION + VERDICT. RESULT-SHA is kept only
  for an executed mutation (decision :allow); on deny it is nil (encoded null)."
  [action verdict & {:keys [result-sha prev-sha actor ts gpg-sig]}]
  (let [decision (verdict-decision verdict)]
    {"ts" (or ts (get action "ts") (now-iso))
     "actor" (or actor (get action "actor") "own-llm")
     "action-sha" (or (get action "action-sha") "")
     "decision" (name decision)
     "reason" (verdict-reason verdict)
     "result-sha" (if (= decision :allow) result-sha nil)
     "prev-sha" (or prev-sha (attest-genesis-sha))
     "gpg-sig" gpg-sig}))

(defn attestation-encode
  "Byte-canonical single-line encoding of an attestation RECORD. This exact
  string is what prev-sha hashes over."
  [record]
  (canon-json
   {"ts" (get record "ts")
    "actor" (get record "actor")
    "action-sha" (get record "action-sha")
    "decision" (get record "decision")
    "reason" (get record "reason")
    "result-sha" (get record "result-sha")
    "prev-sha" (get record "prev-sha")
    "gpg-sig" (get record "gpg-sig")}))

(defn attestation-chain
  "Thread prev-sha through a list of RECORDS (pure). Each record's prev-sha is
  set to sha256(encode(previous)); the first uses the genesis sha. Returns the
  linked records."
  [records]
  (loop [rs records prev (attest-genesis-sha) acc []]
    (if (empty? rs)
      acc
      (let [linked (assoc (first rs) "prev-sha" prev)
            h (sha256-hex (attestation-encode linked))]
        (recur (rest rs) h (conj acc linked))))))

(defn attestation-verify-chain
  "True iff RECORDS form an intact prev-sha chain: each record's prev-sha equals
  sha256(encode(previous)), first == genesis. A middle edit breaks it."
  [records]
  (loop [rs records prev (attest-genesis-sha)]
    (cond
      (empty? rs) true
      (not= (get (first rs) "prev-sha") prev) false
      :else (recur (rest rs) (sha256-hex (attestation-encode (first rs)))))))

;;; ============================================================
;;; §17.6 — Failure modes: append-only audit log
;;; ============================================================
;;;
;;; Best-effort WRITE by default (never breaks the tool path). SAGE_ATTEST_STRICT
;;; aborts the mutation if the record cannot persist. SAGE_ATTEST_DISABLE opts
;;; out of LOGGING only — it NEVER relaxes §17.2 verification. Both flags are
;;; VALUE-gated (§15.3) via affirmative?, not presence. Dynamic vars let tests
;;; override the env defaults without touching the process environment.

(def ^:dynamic *disabled* "Override SAGE_ATTEST_DISABLE (nil ⇒ consult env)." nil)
(def ^:dynamic *strict* "Override SAGE_ATTEST_STRICT (nil ⇒ consult env)." nil)
(def ^:dynamic *log-file* "Override the audit-log path (nil ⇒ default)." nil)
(def ^:dynamic *tap* "Test hook: (fn [record]) called after each attest!." nil)

(defn attest-disabled? []
  (if (nil? *disabled*) (affirmative? (System/getenv "SAGE_ATTEST_DISABLE")) *disabled*))

(defn attest-strict? []
  (if (nil? *strict*) (affirmative? (System/getenv "SAGE_ATTEST_STRICT")) *strict*))

(defn attest-log-file []
  (or *log-file*
      (str (or (System/getenv "SAGE_LOG_DIR")
               (str (System/getProperty "user.dir") "/.logs"))
           "/attest.jsonl")))

;; In-process chain head: sha of the last appended record (or genesis).
(defonce ^:private chain-head (atom (attest-genesis-sha)))
(defn attest-chain-head [] @chain-head)
(defn attest-reset-chain! [] (reset! chain-head (attest-genesis-sha)))

(defn attest-log-append!
  "Append RECORD's byte-canonical encoding as one JSONL line. Returns :skipped
  when logging is disabled, :ok on success, :failed on a swallowed (best-effort)
  write error. When STRICT? and the write fails, THROWS ex-info
  {:type :attest-write-failed} so the caller can abort the mutation (§17.6)."
  [record & {:keys [strict? disabled? log-file]
             :or {strict? (attest-strict?) disabled? (attest-disabled?)
                  log-file (attest-log-file)}}]
  (if disabled?
    :skipped
    (let [line (attestation-encode record)]
      (try
        (let [f (io/file log-file)
              dir (.getParentFile f)]
          (when (and dir (not (.exists dir))) (.mkdirs dir))
          (spit f (str line "\n") :append true)
          :ok)
        (catch Throwable t
          (if strict?
            (throw (ex-info "attest-write-failed"
                            {:type :attest-write-failed :cause (.getMessage t)}))
            :failed))))))

(defn attest!
  "High-level Path A/B entry: build the attestation record for ACTION+VERDICT,
  chain it onto the in-process head, append it (best-effort/strict/disabled), and
  advance the head. Returns the (linked) record. VERIFICATION already happened in
  policy-verdict; this only handles LOGGING (§17.6). Path B callers pass
  :strict? false (Path B is always best-effort, §17.5)."
  [action verdict & {:keys [result-sha actor strict? disabled? log-file]
                     :or {strict? (attest-strict?) disabled? (attest-disabled?)
                          log-file (attest-log-file)}}]
  (let [rec (attestation action verdict
                         :result-sha result-sha :actor actor
                         :prev-sha @chain-head)
        _ (attest-log-append! rec :strict? strict? :disabled? disabled?
                              :log-file log-file)]
    ;; Advance the chain head so the chain stays monotone even when logging is
    ;; disabled/best-effort-failed (a strict write failure threw above).
    (reset! chain-head (sha256-hex (attestation-encode rec)))
    (when *tap* (*tap* rec))
    rec))
