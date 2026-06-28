(ns sajure.json
  "Hand-rolled JSON reader/writer — NO clojure.data.json, NO third-party dep.

  Data model (see README 'spec ambiguities'):
    JSON object  <-> Clojure map with STRING keys      ({} -> {} empty map)
    JSON array   <-> Clojure vector
    JSON string  <-> Clojure String
    JSON number  <-> Clojure Long or Double
    JSON true    <-> true
    JSON false   <-> false
    JSON null    <-> the sentinel `null` (a namespaced keyword), NOT Clojure nil.

  Why a null sentinel? A map key present-but-null must be distinguishable from
  an absent key. `(get m \"id\")` returning nil means *absent*; returning
  `null` means *present and JSON null*. On the WRITE side both Clojure nil and
  the sentinel serialize to the JSON token `null`."
  (:require [clojure.string :as str]))

(def null
  "Sentinel for a parsed JSON `null`. Distinct from Clojure nil (= absent)."
  ::null)

(defn json-null?
  "True if v is the parsed JSON-null sentinel."
  [v]
  (= v null))

;;; ---------------------------------------------------------------------------
;;; Reader (recursive descent over a String + integer index)
;;; ---------------------------------------------------------------------------

(defn- parse-error [msg i]
  (throw (ex-info (str "JSON parse error: " msg " at " i)
                  {:type :json/parse-error :index i})))

(defn- ws? [c]
  (or (= c \space) (= c \tab) (= c \newline) (= c \return)))

(defn- skip-ws [^String s i]
  (let [n (.length s)]
    (loop [i i]
      (if (and (< i n) (ws? (.charAt s i)))
        (recur (inc i))
        i))))

(declare parse-value)

(defn- parse-literal [^String s i lit val]
  (let [end (+ i (count lit))]
    (if (and (<= end (.length s)) (= lit (subs s i end)))
      [val end]
      (parse-error (str "expected " lit) i))))

(defn- parse-string [^String s i]
  ;; i points at the opening quote.
  (let [n (.length s)
        sb (StringBuilder.)]
    (loop [i (inc i)]
      (when (>= i n) (parse-error "unterminated string" i))
      (let [c (.charAt s i)]
        (cond
          (= c \") [(.toString sb) (inc i)]
          (= c \\)
          (let [_ (when (>= (inc i) n) (parse-error "bad escape" i))
                e (.charAt s (inc i))]
            (case e
              \" (do (.append sb \") (recur (+ i 2)))
              \\ (do (.append sb \\) (recur (+ i 2)))
              \/ (do (.append sb \/) (recur (+ i 2)))
              \b (do (.append sb \backspace) (recur (+ i 2)))
              \f (do (.append sb \formfeed) (recur (+ i 2)))
              \n (do (.append sb \newline) (recur (+ i 2)))
              \r (do (.append sb \return) (recur (+ i 2)))
              \t (do (.append sb \tab) (recur (+ i 2)))
              \u (do (when (> (+ i 6) n) (parse-error "bad \\u escape" i))
                     (let [h (subs s (+ i 2) (+ i 6))
                           cp (Integer/parseInt h 16)]
                       (.append sb (char cp))
                       (recur (+ i 6))))
              (parse-error "bad escape char" i)))
          :else (do (.append sb c) (recur (inc i))))))))

(defn- number-char? [c]
  (or (Character/isDigit c)
      (= c \-) (= c \+) (= c \.) (= c \e) (= c \E)))

(defn- parse-number [^String s i]
  (let [n (.length s)]
    (loop [j i]
      (if (and (< j n) (number-char? (.charAt s j)))
        (recur (inc j))
        (let [tok (subs s i j)]
          (when (= tok "") (parse-error "expected number" i))
          (if (re-find #"[.eE]" tok)
            [(Double/parseDouble tok) j]
            [(Long/parseLong tok) j]))))))

(defn- parse-array [^String s i]
  (let [n (.length s)]
    (loop [i (skip-ws s (inc i)) acc (transient [])]
      (when (>= i n) (parse-error "unterminated array" i))
      (if (= (.charAt s i) \])
        [(persistent! acc) (inc i)]
        (let [[v i2] (parse-value s i)
              i3 (skip-ws s i2)]
          (when (>= i3 n) (parse-error "unterminated array" i3))
          (case (.charAt s i3)
            \, (recur (skip-ws s (inc i3)) (conj! acc v))
            \] [(persistent! (conj! acc v)) (inc i3)]
            (parse-error "expected , or ]" i3)))))))

(defn- parse-object [^String s i]
  (let [n (.length s)]
    (loop [i (skip-ws s (inc i)) acc (transient {})]
      (when (>= i n) (parse-error "unterminated object" i))
      (if (= (.charAt s i) \})
        [(persistent! acc) (inc i)]
        (do
          (when (not= (.charAt s i) \") (parse-error "expected string key" i))
          (let [[k i2] (parse-string s i)
                i3 (skip-ws s i2)]
            (when (or (>= i3 n) (not= (.charAt s i3) \:))
              (parse-error "expected :" i3))
            (let [[v i4] (parse-value s (skip-ws s (inc i3)))
                  i5 (skip-ws s i4)]
              (when (>= i5 n) (parse-error "unterminated object" i5))
              (case (.charAt s i5)
                \, (recur (skip-ws s (inc i5)) (assoc! acc k v))
                \} [(persistent! (assoc! acc k v)) (inc i5)]
                (parse-error "expected , or }" i5)))))))))

(defn- parse-value [^String s i]
  (let [n (.length s)]
    (when (>= i n) (parse-error "unexpected end of input" i))
    (let [c (.charAt s i)]
      (cond
        (= c \{) (parse-object s i)
        (= c \[) (parse-array s i)
        (= c \") (parse-string s i)
        (= c \t) (parse-literal s i "true" true)
        (= c \f) (parse-literal s i "false" false)
        (= c \n) (parse-literal s i "null" null)
        (or (Character/isDigit c) (= c \-)) (parse-number s i)
        :else (parse-error (str "unexpected char '" c "'") i)))))

(defn read-str
  "Parse the JSON text in S. Throws ex-info {:type :json/parse-error} on any
  malformed input (incl. trailing garbage, empty/blank). Objects -> string-keyed
  maps, null -> the `null` sentinel."
  [^String s]
  (when (or (nil? s) (str/blank? s))
    (parse-error "empty input" 0))
  (let [[v i] (parse-value s (skip-ws s 0))
        i2 (skip-ws s i)]
    (when (< i2 (.length s))
      (parse-error "trailing content" i2))
    v))

;;; ---------------------------------------------------------------------------
;;; Writer
;;; ---------------------------------------------------------------------------

(defn- write-string [^StringBuilder sb ^String s]
  (.append sb \")
  (dotimes [k (.length s)]
    (let [c (.charAt s k)]
      (case c
        \"        (.append sb "\\\"")
        \\        (.append sb "\\\\")
        \newline  (.append sb "\\n")
        \return   (.append sb "\\r")
        \tab      (.append sb "\\t")
        \backspace (.append sb "\\b")
        \formfeed  (.append sb "\\f")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c)))))
  (.append sb \"))

(defn- write-value [^StringBuilder sb v]
  (cond
    (nil? v)        (.append sb "null")
    (json-null? v)  (.append sb "null")
    (true? v)       (.append sb "true")
    (false? v)      (.append sb "false")
    (string? v)     (write-string sb v)
    (integer? v)    (.append sb (str v))
    (number? v)     (.append sb (str v))
    (map? v)        (do (.append sb \{)
                        (loop [es (seq v) first? true]
                          (when es
                            (when-not first? (.append sb \,))
                            (let [[k val] (first es)]
                              (write-string sb (if (string? k) k (name k)))
                              (.append sb \:)
                              (write-value sb val))
                            (recur (next es) false)))
                        (.append sb \}))
    (or (vector? v)
        (seq? v)
        (set? v))   (do (.append sb \[)
                        (loop [xs (seq v) first? true]
                          (when xs
                            (when-not first? (.append sb \,))
                            (write-value sb (first xs))
                            (recur (next xs) false)))
                        (.append sb \]))
    (keyword? v)    (write-string sb (name v))
    :else           (write-string sb (str v))))

(defn write-str
  "Serialize V to a JSON string. nil and the null sentinel both emit `null`;
  string-keyed maps emit objects; vectors/seqs emit arrays."
  [v]
  (let [sb (StringBuilder.)]
    (write-value sb v)
    (.toString sb)))
