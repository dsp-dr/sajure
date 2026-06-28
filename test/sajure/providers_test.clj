(ns sajure.providers-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sajure.providers :as p]))

(def gen-code (gen/elements [0 400 401 403 404 408 429 500 502 503 418]))
(def gen-provider (gen/elements ["ollama" "gemini" "openai"]))

(defn- utf8-len [^String s] (alength (.getBytes s "UTF-8")))

;;; --- bounded, single-line, control-byte-free excerpt -----------------------
(defspec clean-message-bounded 500
  (prop/for-all [s gen/string]
    (let [out (p/clean-error-message s)
          ;; excerpt = everything except a trailing ellipsis marker
          excerpt (str/replace-first out #"…$" "")]
      (and (string? out)
           ;; ALL control bytes (C0 + DEL) gone — not just whitespace
           (not (re-find #"[\x00-\x1f\x7f]" out))
           (not (str/includes? out "  "))               ; no run of 2 spaces
           ;; the excerpt is bounded in BYTES, not chars
           (<= (utf8-len excerpt) p/error-message-max-bytes)))))

;;; --- control bytes beyond whitespace are neutralized -----------------------
(defspec clean-message-strips-all-control-bytes 300
  (prop/for-all [cp (gen/elements (concat (range 0x00 0x20) [0x7f]))
                 a gen/string-alphanumeric
                 b gen/string-alphanumeric]
    (let [out (p/clean-error-message (str a (char cp) b))]
      (not (str/includes? out (str (char cp)))))))

(deftest clean-message-truncation
  (let [out (p/clean-error-message (apply str (repeat 500 "a")))]
    (is (= 201 (count out)))
    (is (str/ends-with? out "…"))
    (is (= 200 (count (str/replace-first out #"…$" ""))))))

(deftest clean-message-utf8-boundary-safe
  ;; 150 × "猫" = 450 UTF-8 bytes; truncation must NOT split a multibyte char.
  (let [out (p/clean-error-message (apply str (repeat 150 "猫")))
        excerpt (str/replace-first out #"…$" "")]
    (is (str/ends-with? out "…"))
    (is (<= (utf8-len excerpt) p/error-message-max-bytes))
    (is (every? #(= % \猫) excerpt))                     ; no mojibake / split
    (is (= 66 (count excerpt)))))                        ; floor(200/3) = 66 chars

(deftest clean-message-collapse
  (is (= "a b c" (p/clean-error-message "  a\n\n b\t\tc  ")))
  (is (= "del here" (p/clean-error-message (str "del" (char 0x7f) "here"))))
  (is (= "" (p/clean-error-message nil)))
  (is (= "" (p/clean-error-message 123))))

;;; --- normalized line shape: single [..] line -------------------------------
(defspec error-line-shape 400
  (prop/for-all [prov gen-provider code gen-code body gen/string]
    (let [line (p/error-line prov code body)]
      (and (str/starts-with? line "[")
           (str/ends-with? line "]")
           (not (str/includes? line "\n"))))))

;;; --- classification: permanent vs transient --------------------------------
(deftest classification
  (testing "permanent set 401/403/404"
    (doseq [c [401 403 404]] (is (p/permanent? c) (str c))))
  (testing "transient otherwise (incl 0 408 429 5xx)"
    (doseq [c [0 408 429 500 502 503 400 418]] (is (p/transient? c) (str c)))))

(deftest label-specifics
  (is (= "connection failed" (p/error-label "openai" 0)))
  (is (str/starts-with? (p/error-label "openai" 401) "authentication failed"))
  (is (= "rate limited" (p/error-label "gemini" 429)))
  (is (= "server error" (p/error-label "openai" 503)))
  (testing "404 is provider-specific"
    (is (= "model not found" (p/error-label "gemini" 404)))
    (is (= "request failed (HTTP 404)" (p/error-label "openai" 404))))
  (testing "full line uses provider name + label + bounded msg"
    (is (= "[openai connection failed: down]"
           (p/error-line "openai" 0 "down")))))
