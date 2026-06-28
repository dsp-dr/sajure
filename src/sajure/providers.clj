(ns sajure.providers
  "§2 Provider error normalization.

  Every non-200 collapses to ONE clean single-line assistant message:
      [<Provider> <label>: <msg>]
  - <Provider> is the active provider name (ollama/gemini/openai).
  - <label> is a provider-normalized human reason; the STABLE contract is the
    classification + shape, not byte-identical tokens across providers.
  - <msg> is a sanitized bounded excerpt of the body (authoritative spec §2):
    ALL control bytes — C0 (<0x20) AND DEL (0x7f), not just [ \\t\\r\\n] — are
    neutralized to spaces, runs collapsed, trimmed, then truncated to <=200
    BYTES (UTF-8-boundary-safe: never split a multibyte sequence or surrogate
    pair) with a trailing ellipsis when truncated.

  Classification (for the label) is independent of retry policy (§8):
    permanent: 401 403 404      transient: 0 408 429 5xx
  (code 0 is classified transient but the retry SET excludes it — fast-fail.)"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [sajure.config :as config]
            [sajure.http :as http]
            [sajure.json :as json]))

(def ^:const error-message-max-bytes
  "Excerpt cap in BYTES (UTF-8), per the authoritative spec." 200)

;; Back-compat alias (older tests/READMEs referenced a char-length cap).
(def ^:const error-message-max-length error-message-max-bytes)

(defn- utf8-byte-count
  "UTF-8 byte length of a String."
  [^String s]
  (alength (.getBytes s "UTF-8")))

(defn- truncate-utf8-bytes
  "Largest prefix of S whose UTF-8 encoding is <= MAX-BYTES, advancing by whole
  Unicode code points so a multibyte sequence / surrogate pair is never split.
  Returns [prefix truncated?]."
  [^String s max-bytes]
  (let [n (.length s)]
    (loop [i 0 used 0]
      (if (>= i n)
        [s false]
        (let [cp (.codePointAt s i)
              cc (Character/charCount cp)
              cb (utf8-byte-count (String. (Character/toChars cp)))]
          (if (> (+ used cb) max-bytes)
            [(subs s 0 i) true]
            (recur (+ i cc) (+ used cb))))))))

(defn clean-error-message
  "Sanitize a raw body into a bounded single-line excerpt: neutralize every
  control byte (C0 <0x20 and DEL 0x7f) to a space, collapse runs of spaces to
  one, trim, then truncate to <=200 UTF-8 bytes (+ ellipsis when truncated)."
  [s]
  (if-not (string? s)
    ""
    (let [neutralized (str/replace s #"[\x00-\x1f\x7f]" " ")
          collapsed   (str/replace neutralized #" +" " ")
          trimmed     (str/trim collapsed)
          [excerpt truncated?] (truncate-utf8-bytes trimmed error-message-max-bytes)]
      (if truncated? (str excerpt "…") excerpt))))

(def permanent-codes #{401 403 404})

(defn classify
  "Classification bucket for a status CODE (per §2; NOT the retry set)."
  [code]
  (if (contains? permanent-codes code) :permanent :transient))

(defn permanent? [code] (= :permanent (classify code)))
(defn transient? [code] (= :transient (classify code)))

(defn error-label
  "Provider-normalized human-readable reason for CODE. PROVIDER-specific where
  the spec calls for it (e.g. 404)."
  [provider code]
  (let [p (name provider)]
    (cond
      (= code 0)          "connection failed"
      (= code 401)        "authentication failed (invalid/expired key)"
      (= code 403)        "authentication failed (forbidden)"
      (= code 404)        (if (= p "gemini")
                            "model not found"
                            (format "request failed (HTTP %d)" code))
      (= code 429)        "rate limited"
      (<= 500 code 599)   "server error"
      :else               (format "request failed (HTTP %d)" code))))

(defn error-line
  "The normalized single-line assistant message for a non-200 response.
  Shape: [<Provider> <label>: <msg>] — exactly one line, no raw body, no
  thrown value."
  [provider code body]
  (format "[%s %s: %s]"
          (name provider)
          (error-label provider code)
          (clean-error-message (or body ""))))

;;; ===========================================================================
;;; §2 Provider chat transport + response normalization (mirrors guile-sage
;;; provider.scm / ollama.scm / gemini.scm / openai.scm). All HTTP shells to
;;; curl via sajure.http (the spec's transport note); non-200 collapses
;;; through error-line. Pure parsing (normalize-* / *->function-defs) is split
;;; from the IO (chat / chat-streaming / list-models) so it stays testable.
;;;
;;; Normalized response map (keyword keys):
;;;   {:content String                 ; assistant text ("" if only tool calls)
;;;    :tool-calls [{:name s :arguments {string-keyed}}]   ; [] when none
;;;    :prompt-tokens N :completion-tokens N
;;;    :error? bool :guardrails String|nil}
;;; ===========================================================================

(defn current-provider
  "Resolve the active provider from config (SAGE_PROVIDER). `litellm` aliases
  openai. Unknown -> :ollama (mirrors provider.scm)."
  []
  (case (str (or (config/get-config "PROVIDER") "ollama"))
    "ollama"  :ollama
    "gemini"  :gemini
    "openai"  :openai
    "litellm" :openai
    :ollama))

;;; --- config-derived host / model / key -------------------------------------

(defn provider-host [provider]
  (case provider
    :ollama (or (config/get-config "OLLAMA_HOST") "http://localhost:11434")
    :gemini (or (config/get-config "GEMINI_HOST")
                "https://generativelanguage.googleapis.com")
    :openai (or (config/get-config "OPENAI_BASE") "http://localhost:4000")))

(defn provider-model [provider]
  (or (config/get-config "MODEL")
      (case provider
        :ollama "llama3.2:latest"
        :gemini "gemini-2.5-flash"
        :openai "gpt-4o-mini")))

(defn- jget
  "Get K from a parsed-JSON map M, treating the JSON-null sentinel as absent."
  ([m k] (jget m k nil))
  ([m k default]
   (let [v (get m k ::missing)]
     (cond (= v ::missing) default
           (json/json-null? v) default
           :else v))))

(defn- num-or-0 [v] (if (number? v) v 0))

;;; --- tool schema projection (registry -> provider "function" defs) ---------

(defn tools->function-defs
  "Project a REGISTRY (vector of tool maps) to the OpenAI/Ollama `tools` array
  shape: [{\"type\":\"function\",\"function\":{name,description,parameters}}]."
  [registry]
  (mapv (fn [t]
          {"type" "function"
           "function" {"name" (:name t)
                       "description" (:description t)
                       "parameters" (:schema t)}})
        registry))

(defn tools->gemini-decls
  "Gemini functionDeclarations shape."
  [registry]
  [{"functionDeclarations"
    (mapv (fn [t]
            {"name" (:name t)
             "description" (:description t)
             "parameters" (:schema t)})
          registry)}])

;;; --- response normalization (pure) -----------------------------------------

(defn- normalize-tool-call-ollama [tc]
  (let [f (jget tc "function")
        nm (and f (jget f "name"))
        args (and f (jget f "arguments"))]
    (when nm {:name nm :arguments (if (map? args) args {})})))

(defn normalize-ollama
  "Normalize a parsed Ollama /api/chat response."
  [parsed]
  (let [msg (jget parsed "message" {})
        content (or (jget msg "content") "")
        tcs (keep normalize-tool-call-ollama
                  (let [v (jget msg "tool_calls" [])] (if (vector? v) v [])))]
    {:content (if (string? content) content "")
     :tool-calls (vec tcs)
     :prompt-tokens (num-or-0 (jget parsed "prompt_eval_count" 0))
     :completion-tokens (num-or-0 (jget parsed "eval_count" 0))
     :error? false :guardrails nil}))

(defn- normalize-tool-call-openai [tc]
  (let [f (jget tc "function")
        nm (and f (jget f "name"))
        raw (and f (jget f "arguments"))
        ;; OpenAI sends arguments as a JSON STRING — parse to a map.
        args (cond (map? raw) raw
                   (string? raw) (try (json/read-str raw) (catch Exception _ {}))
                   :else {})]
    (when nm {:name nm :arguments (if (map? args) args {})})))

(defn normalize-openai
  "Normalize a parsed OpenAI-shape /chat/completions response."
  [parsed]
  (let [choices (let [v (jget parsed "choices" [])] (if (vector? v) v []))
        msg (if (seq choices) (jget (first choices) "message" {}) {})
        content (or (jget msg "content") "")
        tcs (keep normalize-tool-call-openai
                  (let [v (jget msg "tool_calls" [])] (if (vector? v) v [])))
        usage (jget parsed "usage" {})]
    {:content (if (string? content) content "")
     :tool-calls (vec tcs)
     :prompt-tokens (num-or-0 (jget usage "prompt_tokens" 0))
     :completion-tokens (num-or-0 (jget usage "completion_tokens" 0))
     :error? false :guardrails nil}))

(defn normalize-gemini
  "Normalize a parsed Gemini :generateContent response."
  [parsed]
  (let [cands (let [v (jget parsed "candidates" [])] (if (vector? v) v []))
        parts (if (seq cands)
                (let [c (jget (first cands) "content" {})
                      p (jget c "parts" [])] (if (vector? p) p []))
                [])
        texts (keep #(jget % "text") parts)
        tcs (keep (fn [p]
                    (when-let [fc (jget p "functionCall")]
                      (let [nm (jget fc "name")
                            args (jget fc "args" {})]
                        (when nm {:name nm :arguments (if (map? args) args {})}))))
                  parts)
        usage (jget parsed "usageMetadata" {})]
    {:content (apply str texts)
     :tool-calls (vec tcs)
     :prompt-tokens (num-or-0 (jget usage "promptTokenCount" 0))
     :completion-tokens (num-or-0 (jget usage "candidatesTokenCount" 0))
     :error? false :guardrails nil}))

;;; --- gemini message conversion (pure) --------------------------------------

(defn messages->gemini-contents
  "Convert [{\"role\" \"content\"}] to Gemini contents. system/user collapse to
  role `user`, assistant -> `model` (mirrors gemini.scm gemini-convert-messages)."
  [messages]
  (mapv (fn [m]
          (let [role (get m "role")
                grole (if (= role "assistant") "model" "user")]
            {"role" grole "parts" [{"text" (or (get m "content") "")}]}))
        messages))

;;; --- error / parse helpers -------------------------------------------------

(defn- error-response [provider code body]
  {:content (error-line provider code body)
   :tool-calls [] :prompt-tokens 0 :completion-tokens 0
   :error? true :guardrails nil})

(defn- parse-or-error [provider normalize-fn {:keys [code body]}]
  (if (= code 200)
    (try (normalize-fn (json/read-str body))
         (catch Exception _ (error-response provider code body)))
    (error-response provider code body)))

;;; --- per-provider request bodies + endpoints -------------------------------

(defn- ollama-body [model messages registry stream?]
  (json/write-str
   (cond-> {"model" model "messages" (vec messages) "stream" stream?}
     (seq registry) (assoc "tools" (tools->function-defs registry)))))

(defn- openai-body [model messages registry stream?]
  (json/write-str
   (cond-> {"model" model "messages" (vec messages) "stream" stream?}
     (seq registry) (assoc "tools" (tools->function-defs registry)))))

(defn- gemini-body [messages registry]
  (json/write-str
   (cond-> {"contents" (messages->gemini-contents messages)}
     (seq registry) (assoc "tools" (tools->gemini-decls registry)))))

(defn- ollama-headers [] {"Content-Type" "application/json"})

(defn- openai-headers []
  (cond-> {"Content-Type" "application/json"}
    (config/get-config "OPENAI_API_KEY")
    (assoc "Authorization" (str "Bearer " (config/get-config "OPENAI_API_KEY")))))

(defn- gemini-headers []
  (cond-> {"Content-Type" "application/json"}
    (config/get-config "GEMINI_API_KEY")
    (assoc "x-goog-api-key" (config/get-config "GEMINI_API_KEY"))))

;;; --- public chat (non-streaming) -------------------------------------------

(defn chat
  "Non-streaming chat with optional tool calling. Shells to curl with the §8
  retry policy and returns a normalized response map. REGISTRY (may be empty)
  supplies the tool defs. Never throws: a non-200 / parse failure collapses to
  an :error? response whose :content is the normalized error-line."
  ([model messages registry] (chat (current-provider) model messages registry))
  ([provider model messages registry]
   (try
     (case provider
       :ollama (parse-or-error
                :ollama normalize-ollama
                (http/http-post-with-retry (str (provider-host :ollama) "/api/chat")
                                           (ollama-body model messages registry false)
                                           :headers (ollama-headers)))
       :openai (parse-or-error
                :openai normalize-openai
                (http/http-post-with-retry (str (provider-host :openai) "/chat/completions")
                                           (openai-body model messages registry false)
                                           :headers (openai-headers)))
       :gemini (parse-or-error
                :gemini normalize-gemini
                (http/http-post-with-retry
                 (str (provider-host :gemini) "/v1beta/models/" model ":generateContent")
                 (gemini-body messages registry)
                 :headers (gemini-headers))))
     (catch Throwable t
       (error-response provider 0 (str "client error: " (.getMessage t)))))))

;;; --- streaming -------------------------------------------------------------
;;; Ollama emits NDJSON (one JSON object per line). We run curl via
;;; ProcessBuilder and parse each line as it arrives, invoking ON-TOKEN with the
;;; incremental message.content and accumulating tool_calls (which Ollama can put
;;; in any chunk). openai/gemini SSE delta streaming is PARTIAL: we fall back to
;;; a single non-stream call and emit the whole content as one token.
;;; TODO(spec §2 streaming): true SSE delta parsing for openai/gemini.

(defn- ollama-stream*
  "Real NDJSON streaming for ollama via ProcessBuilder(curl). Returns a
  normalized response. ON-TOKEN is called per content fragment."
  [model messages registry on-token]
  (let [url (str (provider-host :ollama) "/api/chat")
        body (ollama-body model messages registry true)
        pb (ProcessBuilder.
            ^java.util.List
            ["curl" "-sS" "--connect-timeout" "5" "-X" "POST"
             "-H" "Content-Type: application/json" "-d" body url])]
    (.redirectErrorStream pb false)
    (let [proc (.start pb)
          rdr (io/reader (.getInputStream proc))]
      (try
        (loop [acc "" tool-calls [] pt 0 ct 0]
          (let [line (.readLine ^java.io.BufferedReader rdr)]
            (if (nil? line)
              (do (.waitFor proc)
                  {:content acc :tool-calls (vec tool-calls)
                   :prompt-tokens pt :completion-tokens ct
                   :error? false :guardrails nil})
              (let [chunk (try (json/read-str line) (catch Exception _ nil))
                    msg (and chunk (jget chunk "message" {}))
                    frag (and msg (jget msg "content"))
                    tcs (and msg (jget msg "tool_calls"))
                    new-tcs (if (vector? tcs)
                              (into tool-calls (keep normalize-tool-call-ollama tcs))
                              tool-calls)]
                (when (and (string? frag) (seq frag)) (on-token frag))
                (recur (str acc (when (string? frag) frag))
                       new-tcs
                       (num-or-0 (or (and chunk (jget chunk "prompt_eval_count")) pt))
                       (num-or-0 (or (and chunk (jget chunk "eval_count")) ct)))))))
        (catch Throwable t
          (error-response :ollama 0 (str "stream error: " (.getMessage t))))
        (finally (.destroy proc))))))

(defn chat-streaming
  "Streaming chat. ON-TOKEN is invoked with each content fragment. ollama uses
  real NDJSON streaming; openai/gemini fall back to non-streaming then emit the
  full content once (see TODO above). Returns a normalized response map."
  ([model messages registry on-token]
   (chat-streaming (current-provider) model messages registry on-token))
  ([provider model messages registry on-token]
   (case provider
     :ollama (try (ollama-stream* model messages registry on-token)
                  (catch Throwable t (error-response :ollama 0 (.getMessage t))))
     (let [resp (chat provider model messages registry)]
       (when (seq (:content resp)) (on-token (:content resp)))
       resp))))

;;; --- model listing ---------------------------------------------------------

(defn list-models
  "List available model names for PROVIDER (a vector of strings). Never throws."
  ([] (list-models (current-provider)))
  ([provider]
   (try
     (case provider
       :ollama (let [{:keys [code body]} (http/http-get (str (provider-host :ollama) "/api/tags")
                                                        :headers (ollama-headers))]
                 (if (= code 200)
                   (mapv #(jget % "name") (let [v (jget (json/read-str body) "models" [])]
                                            (if (vector? v) v [])))
                   []))
       :openai (let [{:keys [code body]} (http/http-get (str (provider-host :openai) "/models")
                                                        :headers (openai-headers))]
                 (if (= code 200)
                   (mapv #(or (jget % "id") (jget % "name"))
                         (let [v (jget (json/read-str body) "data" [])]
                           (if (vector? v) v [])))
                   []))
       :gemini (let [{:keys [code body]} (http/http-get (str (provider-host :gemini) "/v1beta/models")
                                                        :headers (gemini-headers))]
                 (if (= code 200)
                   (mapv #(jget % "name") (let [v (jget (json/read-str body) "models" [])]
                                            (if (vector? v) v [])))
                   [])))
     (catch Throwable _ []))))
