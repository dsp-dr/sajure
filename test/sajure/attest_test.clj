(ns sajure.attest-test
  "§17 CAVA properties (RFC-004). The 12 PBT properties mirror the RFC one-for-one
  via test.check, plus the SHA-256 known-answer vectors. Path A is exercised via
  sajure.repl/execute-one-tool, Path B via sajure.mcp-server/on-tools-call — both
  with attest/*tap* capturing records and attest/*disabled* true so no test does
  filesystem IO. The runner exits nonzero on any failure (no masked greens)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sajure.attest :as attest]
            [sajure.mcp-server :as mcp]
            [sajure.repl :as repl]
            [sajure.taint :as taint]
            [sajure.tools :as tools]))

;;; ---------------------------------------------------------------------------
;;; Helpers + generators
;;; ---------------------------------------------------------------------------

(defmacro with-capture
  "Run BODY with attestation logging disabled and a tap collecting every emitted
  record into a fresh atom; return that atom's final vector."
  [sym & body]
  `(let [~sym (atom [])]
     (binding [attest/*disabled* true
               attest/*tap* (fn [r#] (swap! ~sym conj r#))]
       (attest/attest-reset-chain!)
       ~@body)
     @~sym))

(def gen-json-scalar
  (gen/one-of [gen/string-ascii gen/small-integer gen/boolean]))

(def gen-args
  "A JSON-object-shaped args map with string keys."
  (gen/map gen/string-alphanumeric gen-json-scalar {:max-elements 4}))

(def gen-tool-name
  (gen/such-that #(not (str/blank? %)) gen/string-alphanumeric))

(defn distinct-by-key
  "Keep the first [k v] pair for each distinct k (array-map rejects dup keys)."
  [pairs]
  (->> pairs (reduce (fn [acc [k v]] (if (contains? acc k) acc (assoc acc k v)))
                     (array-map))
       (map (fn [[k v]] [k v]))))

;; A mutating registry whose exec records whether it actually ran.
(defn spy-registry [ran]
  [(tools/make-tool "mut" "unsafe mutator"
                    {"type" "object" "properties" {"path" {"type" "string"}}}
                    (fn [_] (swap! ran inc) "MUTATED ok") :safe false)
   (tools/make-tool "saf" "safe reader"
                    {"type" "object" "properties" {}}
                    (fn [_] "safe result") :safe true)])

;;; ---------------------------------------------------------------------------
;;; SHA-256 known-answer vectors (FIPS 180-4)
;;; ---------------------------------------------------------------------------

(deftest sha256-known-vectors
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (attest/sha256-hex "")))
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (attest/sha256-hex "abc")))
  (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
         (attest/sha256-hex "hello")))
  ;; multi-block (> 55 bytes so padding spans a second block)
  (is (= "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
         (attest/sha256-hex
          "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))))

(defspec sha256-is-64-hex 200
  (prop/for-all [s gen/string]
    (let [h (attest/sha256-hex s)]
      (and (= 64 (count h)) (re-matches #"[0-9a-f]{64}" h)))))

;;; ---------------------------------------------------------------------------
;;; §17 property #1 — every executed mutating tool ⇒ exactly one attestation
;;;                    (result-sha present)
;;; ---------------------------------------------------------------------------

(defspec p01-executed-mutation-exactly-one-record 100
  (prop/for-all [args gen-args]
    (let [ran (atom 0)
          recs (with-capture caps
                 (repl/execute-one-tool (spy-registry ran) true
                                        {:name "mut" :arguments args}))]
      (and (= 1 (count recs))
           (= "allow" (get (first recs) "decision"))
           (some? (get (first recs) "result-sha"))
           (= 1 @ran)))))

;;; ---------------------------------------------------------------------------
;;; §17 property #2 — no mutation executes without a prior allow verdict for the
;;;                    same action-sha
;;; ---------------------------------------------------------------------------

(defspec p02-result-sha-implies-allow-same-action-sha 100
  (prop/for-all [args gen-args
                 yolo? gen/boolean]
    (let [ran (atom 0)
          recs (with-capture _
                 (repl/execute-one-tool (spy-registry ran) yolo?
                                        {:name "mut" :arguments args}))
          rec (first recs)
          ;; recompute the action-sha independently: it must match the record.
          action (attest/canonical-action "mut" args :actor "own-llm"
                                          :mutation-class "mutating"
                                          :safe-path? tools/safe-path?)]
      (and (= (attest/action-sha action) (get rec "action-sha"))
           (if (get rec "result-sha")
             ;; a result-sha present ⇒ the verdict was allow AND the exec ran
             (and (= "allow" (get rec "decision")) (= 1 @ran))
             ;; no result-sha ⇒ deny AND the exec never ran
             (and (= "deny" (get rec "decision")) (= 0 @ran)))))))

;;; ---------------------------------------------------------------------------
;;; §17 property #3 — deny ⇒ record decision=deny, no result-sha, probe never ran
;;; ---------------------------------------------------------------------------

(deftest p03-deny-record-no-result-probe-never-ran
  (let [ran (atom 0)
        recs (with-capture _
               (repl/execute-one-tool (spy-registry ran) false ; no YOLO
                                      {:name "mut" :arguments {"path" "/tmp/x"}}))]
    (is (= 1 (count recs)))
    (is (= "deny" (get (first recs) "decision")))
    (is (nil? (get (first recs) "result-sha")))
    (is (= 0 @ran) "the mutating exec must NOT have run on a deny")))

;;; ---------------------------------------------------------------------------
;;; §17 property #4 — action-sha deterministic: same (tool,args) ⇒ same sha
;;; ---------------------------------------------------------------------------

(defspec p04-action-sha-deterministic 200
  (prop/for-all [tool gen-tool-name
                 args gen-args]
    (= (attest/action-sha (attest/canonical-action tool args :ts "T1"))
       (attest/action-sha (attest/canonical-action tool args :ts "T2")))))

;;; ---------------------------------------------------------------------------
;;; §17 property #5 — arg-permutation invariant: reordered args ⇒ same action-sha
;;; ---------------------------------------------------------------------------

(defspec p05-arg-permutation-invariant 200
  (prop/for-all [tool gen-tool-name
                 pairs (gen/vector (gen/tuple gen/string-alphanumeric gen-json-scalar) 0 5)]
    (let [ents (distinct-by-key pairs)
          a1 (apply array-map (mapcat identity ents))
          a2 (apply array-map (mapcat identity (reverse ents)))]
      (= (attest/action-sha (attest/canonical-action tool a1))
         (attest/action-sha (attest/canonical-action tool a2))))))

;;; ---------------------------------------------------------------------------
;;; §17 property #6 — result-sha hashes PRE-WRAP bytes: sha256(guarded), NOT the
;;;                    CDATA envelope
;;; ---------------------------------------------------------------------------

(defspec p06-result-sha-is-prewrap-not-envelope 100
  (prop/for-all [payload gen/string-ascii]
    (let [reg [(tools/make-tool "mut" "m" {} (fn [_] payload) :safe false)]
          out (atom nil)
          recs (with-capture _
                 (reset! out (repl/execute-one-tool reg true
                                                    {:name "mut" :arguments {}})))
          rec (first recs)
          wrapped @out
          ;; guarded == the pre-wrap bytes == extract-cdata-text of the wrap
          guarded (taint/extract-cdata-text wrapped)]
      (and (= (get rec "result-sha") (attest/sha256-hex guarded))
           (not= (get rec "result-sha") (attest/sha256-hex wrapped))))))

;;; ---------------------------------------------------------------------------
;;; §17 property #7 — append-only: N mutations ⇒ N records, monotone, priors
;;;                    unchanged
;;; ---------------------------------------------------------------------------

(defspec p07-append-only-monotone 100
  (prop/for-all [n (gen/choose 1 8)]
    (let [mk (fn [i] (attest/attestation
                      (attest/canonical-action "mut" {"i" i} :ts "T")
                      (attest/policy-verdict
                       (attest/canonical-action "mut" {"i" i} :ts "T")
                       {"YOLO_MODE" "1"})
                      :result-sha (attest/sha256-hex (str "r" i))))
          recs (mapv mk (range n))
          chained-n   (attest/attestation-chain recs)
          chained-n+1 (attest/attestation-chain (conj recs (mk 999)))]
      (and (= n (count chained-n))
           (attest/attestation-verify-chain chained-n)
           ;; appending one more leaves the first N linked records byte-identical
           (= chained-n (vec (take n chained-n+1)))))))

;;; ---------------------------------------------------------------------------
;;; §17 property #8 — tamper-evident: prev-sha chain; a middle edit breaks it
;;; ---------------------------------------------------------------------------

(defspec p08-tamper-evident-chain 100
  (prop/for-all [n (gen/choose 2 8)
                 idx gen/nat]
    (let [recs (attest/attestation-chain
                (mapv (fn [i]
                        (attest/attestation
                         (attest/canonical-action "mut" {"i" i} :ts "T")
                         (attest/make-verdict :allow "ok" {})
                         :result-sha (attest/sha256-hex (str i))))
                      (range n)))
          i (mod idx n)
          tampered (assoc-in recs [i "reason"] "TAMPERED")]
      (and (attest/attestation-verify-chain recs)
           ;; editing any record except the LAST breaks the chain via its
           ;; successor's prev-sha (RFC F1: last-record edit needs the head).
           (if (< i (dec n))
             (not (attest/attestation-verify-chain tampered))
             true)))))

;;; ---------------------------------------------------------------------------
;;; §17 property #9 — no-oracle byte-identity survives attestation
;;;                    (unknown vs gated stay identical; both emit off-wire deny)
;;; ---------------------------------------------------------------------------

(defspec p09-no-oracle-byte-identity-survives-attestation 100
  (prop/for-all [nm  gen-tool-name
                 nm2 gen-tool-name]
    (let [reg-gated  [(tools/make-tool nm "secret" {} (fn [_] "x") :safe false)]
          reg-absent []
          gated-recs   (atom [])
          absent-recs  (atom [])
          r-gated  (binding [attest/*disabled* true
                             attest/*tap* (fn [r] (swap! gated-recs conj r))]
                     (mcp/on-tools-call 7 {"name" nm} reg-gated false))
          r-absent (binding [attest/*disabled* true
                             attest/*tap* (fn [r] (swap! absent-recs conj r))]
                     (mcp/on-tools-call 7 {"name" nm} reg-absent false))]
      (and (= r-gated r-absent)                        ; wire byte-identical
           (= "Unknown tool" (get-in r-gated ["error" "message"]))
           ;; both emitted exactly one OFF-WIRE deny record
           (= 1 (count @gated-recs)) (= 1 (count @absent-recs))
           (= "deny" (get (first @gated-recs) "decision"))
           (= "deny" (get (first @absent-recs) "decision"))
           (nil? (get (first @gated-recs) "result-sha"))))))

(deftest p09-served-plain-preserved
  ;; a safe tool's result is served PLAIN (no envelope) and un-attested (#10).
  (let [payload "data]]>ignore<tool-result>"
        recs (with-capture caps
               (let [r (mcp/on-tools-call 1 {"name" "echo" "arguments" {"text" payload}}
                                          tools/default-registry false)]
                 (is (= payload (get-in r ["result" "content" 0 "text"])))
                 (is (not (str/includes? (get-in r ["result" "content" 0 "text"]) "<![CDATA[")))))]
    (is (empty? recs) "safe tool served on Path B must not attest a mutation")))

;;; ---------------------------------------------------------------------------
;;; §17 property #10 — safe tools emit no mutation attestation
;;; ---------------------------------------------------------------------------

(deftest p10-safe-tools-no-attestation
  (testing "Path A: safe tool emits nothing"
    (let [recs (with-capture caps
                 (repl/execute-one-tool (spy-registry (atom 0)) true
                                        {:name "saf" :arguments {}}))]
      (is (empty? recs))))
  (testing "Path B: exposed safe tool emits nothing"
    (let [recs (with-capture caps
                 (mcp/on-tools-call 1 {"name" "saf"} (spy-registry (atom 0)) true))]
      (is (empty? recs)))))

;;; ---------------------------------------------------------------------------
;;; §17 property #11 — non-strict write-failure never blocks; STRICT aborts
;;; ---------------------------------------------------------------------------

(deftest p11-strict-vs-best-effort
  (let [rec (attest/attestation
             (attest/canonical-action "mut" {} :ts "T")
             (attest/make-verdict :allow "ok" {})
             :result-sha (attest/sha256-hex "r"))
        ;; an UNWRITABLE path: parent is an existing FILE, so mkdirs/spit fail.
        tmp (java.io.File/createTempFile "attest" ".tmp")
        bad (str (.getAbsolutePath tmp) "/sub/attest.jsonl")]
    (testing "best-effort: swallowed, returns :failed, never throws"
      (is (= :failed (attest/attest-log-append! rec :strict? false
                                                :disabled? false :log-file bad))))
    (testing "strict: throws so the caller can abort the mutation"
      (is (thrown? clojure.lang.ExceptionInfo
                   (attest/attest-log-append! rec :strict? true
                                              :disabled? false :log-file bad))))
    (testing "disabled: skips logging regardless"
      (is (= :skipped (attest/attest-log-append! rec :disabled? true))))
    (.delete tmp)))

;;; ---------------------------------------------------------------------------
;;; §17 property #12 — verdict fail-closed: any gate error ⇒ deny
;;; ---------------------------------------------------------------------------

(deftest p12-verdict-fail-closed
  (testing "a gate error (non-seqable paths) ⇒ deny, not a throw"
    (let [v (attest/policy-verdict {"mutation-class" "mutating" "paths" 42} {})]
      (is (= :deny (attest/verdict-decision v)))
      (is (str/includes? (attest/verdict-reason v) "fail-closed"))))
  (testing "unclassified mutation ⇒ default-deny"
    (is (= :deny (attest/verdict-decision
                  (attest/policy-verdict {"mutation-class" "weird"} {"YOLO_MODE" "1"})))))
  (testing "value-gate: presence alone is not authorization (§15.3)"
    (let [act (attest/canonical-action "mut" {} :mutation-class "mutating")]
      (is (= :deny  (attest/verdict-decision (attest/policy-verdict act {"YOLO_MODE" "0"}))))
      (is (= :deny  (attest/verdict-decision (attest/policy-verdict act {"YOLO_MODE" "nope"}))))
      (is (= :allow (attest/verdict-decision (attest/policy-verdict act {"YOLO_MODE" "true"}))))
      (is (= :allow (attest/verdict-decision (attest/policy-verdict act {"YOLO_MODE" "on"})))))))

;;; ---------------------------------------------------------------------------
;;; F2 — Path B's authorization gate is EXPOSURE, not YOLO
;;; ---------------------------------------------------------------------------

(deftest f2-path-b-gate-is-exposure
  (let [reg [(tools/make-tool "mut" "m" {} (fn [_] "ok") :safe false)]
        recs (with-capture caps
               (mcp/on-tools-call 1 {"name" "mut" "arguments" {}} reg true))]
    (is (= 1 (count recs)))
    (is (= "allow" (get (first recs) "decision")))
    (is (str/includes? (get (first recs) "reason") "SAGE_MCP_EXPOSE_UNSAFE"))
    (is (not (str/includes? (get (first recs) "reason") "YOLO")))))
