(ns sajure.http
  "§8 HTTP transport & resilience.

  All HTTP shells out to curl with --connect-timeout 5. The startup model probe
  (http-get) fails fast — no retry. Chat (http-post-with-retry) retries the
  TRANSIENT SET 408/429/5xx with exponential backoff, SAGE_HTTP_MAX_RETRIES
  (default 2). Connection failure (curl exit != 0 -> code 0) FAILS FAST (a retry
  costs a full connect-timeout for no gain)."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [sajure.config :as config]))

(def ^:const connect-timeout-secs 5)

;; The RETRY set (NOT the classification set). 408/429/5xx only; 0 excluded.
(defn retryable-code?
  "Is CODE in the retry set 408/429/5xx? Code 0 (connection failed) is NOT."
  [code]
  (or (= code 408) (= code 429) (<= 500 code 599)))

(defn max-retries
  "SAGE_HTTP_MAX_RETRIES (default 2; 0 disables retry)."
  []
  (let [v (config/get-config "HTTP_MAX_RETRIES")]
    (if (and v (re-matches #"\d+" v)) (Integer/parseInt v) 2)))

(defn- run-curl
  "Run curl with ARGS; return {:code http-status :body s}. A nonzero curl exit
  (connection/timeout/DNS) maps to code 0 with the stderr as the body."
  [args]
  (let [res (apply shell/sh "curl" "-sS" "--connect-timeout"
                   (str connect-timeout-secs)
                   "-w" "\n%{http_code}" args)]
    (if (zero? (:exit res))
      (let [out (:out res)
            nl (.lastIndexOf ^String out "\n")
            code-str (if (neg? nl) "" (subs out (inc nl)))
            body (if (neg? nl) out (subs out 0 nl))]
        {:code (try (Integer/parseInt (str/trim code-str)) (catch Exception _ 0))
         :body body})
      ;; curl failed to connect / timed out
      {:code 0 :body (str/trim (or (:err res) "connection failed"))})))

(defn http-get
  "GET URL. Fails fast (no retry) — the startup probe path."
  [url & {:keys [headers]}]
  (run-curl (concat (mapcat (fn [[k v]] ["-H" (str k ": " v)]) headers)
                    [url])))

(defn http-post
  "Single POST attempt. Returns {:code :body}."
  [url body & {:keys [headers]}]
  (run-curl (concat ["-X" "POST"]
                    (mapcat (fn [[k v]] ["-H" (str k ": " v)]) headers)
                    ["-d" body url])))

(defn with-retry
  "Generic resilience driver, pure of curl so it is property-testable.
  REQUEST-FN is a thunk returning {:code :body}. Retries while the response is
  in the retry set and attempts remain, sleeping (SLEEP-FN ms) with exponential
  backoff (base 250ms * 2^attempt). Returns the final {:code :body}."
  ([request-fn] (with-retry request-fn (max-retries) (fn [ms] (Thread/sleep ms))))
  ([request-fn retries sleep-fn]
   (loop [attempt 0]
     (let [{:keys [code] :as resp} (request-fn)]
       (if (and (retryable-code? code) (< attempt retries))
         (do (sleep-fn (* 250 (long (Math/pow 2 attempt))))
             (recur (inc attempt)))
         resp)))))

(defn http-post-with-retry
  "Chat POST with the §8 retry policy."
  [url body & {:keys [headers]}]
  (with-retry #(http-post url body :headers headers)))
