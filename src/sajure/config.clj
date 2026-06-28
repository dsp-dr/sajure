(ns sajure.config
  "§11 Config precedence + XDG dirs.

  Precedence (highest first):  SAGE_<KEY> env  ->  <KEY> env  ->  dotenv
  (.env, then ~/.config/sage/.env)  ->  default."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- getenv [k] (System/getenv k))

(defn home [] (or (getenv "HOME") (System/getProperty "user.home")))

(defn xdg-config-home []
  (or (getenv "XDG_CONFIG_HOME") (str (home) "/.config")))

(defn xdg-data-home []
  (or (getenv "XDG_DATA_HOME") (str (home) "/.local/share")))

(defn sessions-dir
  "Per §6: sessions live under XDG data home."
  []
  (str (xdg-data-home) "/sage/sessions"))

(defn- parse-dotenv
  "Parse KEY=VALUE lines; ignore blanks and #comments. Strips one layer of
  surrounding single/double quotes. Returns a string->string map."
  [^String text]
  (reduce
   (fn [m line]
     (let [line (str/trim line)]
       (if (or (str/blank? line) (str/starts-with? line "#"))
         m
         (let [idx (str/index-of line "=")]
           (if (nil? idx)
             m
             (let [k (str/trim (subs line 0 idx))
                   v (str/trim (subs line (inc idx)))
                   v (if (and (>= (count v) 2)
                              (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                                  (and (str/starts-with? v "'") (str/ends-with? v "'"))))
                       (subs v 1 (dec (count v)))
                       v)]
               (assoc m k v)))))))
   {}
   (str/split-lines text)))

(defn- read-dotenv [path]
  (let [f (io/file path)]
    (if (.exists f)
      (try (parse-dotenv (slurp f)) (catch Exception _ {}))
      {})))

(defn dotenv
  "Merged dotenv: .env in cwd overlaid by ~/.config/sage/.env? No — cwd .env
  wins (read last). Returns string->string."
  []
  (merge (read-dotenv (str (xdg-config-home) "/sage/.env"))
         (read-dotenv ".env")))

(defn get-config
  "Resolve KEY (e.g. \"PROVIDER\") by the §11 precedence. Returns DEFAULT (nil
  if unspecified) when unset everywhere."
  ([key] (get-config key nil))
  ([key default]
   ;; §11 (spec v3+): dotenv SHADOWS the shell env (the reference's intentional,
   ;; if unconventional, behavior) — dotenv entries checked BEFORE getenv.
   (let [env (dotenv)]
     (or (get env (str "SAGE_" key))
         (get env key)
         (getenv (str "SAGE_" key))
         (getenv key)
         default))))

(defn flag?
  "True when KEY resolves to the string \"1\"."
  [key]
  (= "1" (get-config key)))

;;; ---------------------------------------------------------------------------
;;; §6 Per-model context-window token limits (mirrors config.scm *token-limits*).
;;; Ordered: most-specific substrings FIRST (first match wins, like the Guile
;;; loop). Lookup is by case-insensitive substring of the model name.
;;; ---------------------------------------------------------------------------

(def token-limits
  "Ordered [model-substring limit] pairs; first substring match wins."
  [["glm-4.7" 128000]
   ["glm-4.6" 200000]
   ["qwen3-coder" 32000]
   ["qwen" 32000]
   ["llama3" 8000]
   ["llama" 4096]
   ["mistral" 8000]
   ["deepseek" 64000]
   ["gpt-4o" 128000]
   ["gpt-4" 8000]
   ["claude" 200000]
   ["gemini-2.5-pro" 2000000]
   ["gemini-2.5-flash-lite" 1000000]
   ["gemini-2.5-flash" 1000000]
   ["gemini-1.5-pro" 2000000]
   ["gemini-1.5-flash" 128000]
   ["gemini" 128000]
   ["local" 8000]
   ["cloud" 64000]])

(defn- local-provider? []
  (let [host (or (get-config "OLLAMA_HOST") "http://localhost:11434")]
    (boolean (some #(str/includes? host %)
                   ["localhost" "127.0.0.1" ".local" ".lan"]))))

(defn get-token-limit
  "Resolve the context-window token limit for MODEL (per get-token-limit in
  config.scm). Priority: model-substring match > TOKEN_LIMIT override >
  provider default (local 8000 / cloud 64000) > 4000 ultimate fallback."
  ([] (get-token-limit nil))
  ([model]
   (let [model-limit (when model
                       (let [lc (str/lower-case model)]
                         (some (fn [[sub lim]] (when (str/includes? lc sub) lim))
                               token-limits)))
         explicit (when-not model-limit
                    (let [v (get-config "TOKEN_LIMIT")]
                      (when (and v (re-matches #"\d+" v)) (Long/parseLong v))))
         provider-default (when (and (not model-limit) (not explicit))
                            (if (local-provider?) 8000 64000))]
     (or model-limit explicit provider-default 4000))))
