(ns sajure.session-test
  "§6 sessions, context thresholds, compaction. Pure transforms tested directly;
  persistence tested through a redirected sessions-dir (no real XDG writes)."
  (:require [clojure.test :refer [deftest is testing]]
            [sajure.config :as config]
            [sajure.session :as s]))

;;; --- token estimation ------------------------------------------------------
(deftest token-estimate
  (is (= 0 (s/estimate-tokens "")))
  (is (= 1 (s/estimate-tokens "abcd")))
  (is (= 2 (s/estimate-tokens "abcde")))         ; ceil(5/4)
  (is (= 0 (s/estimate-tokens nil))))

;;; --- add-message stats accounting ------------------------------------------
(deftest add-message-stats
  (let [s0 (s/new-session {:model "llama3"})
        s1 (s/add-message s0 "user" "hello there friend" :tokens 5)
        s2 (s/add-message s1 "assistant" "hi" :tokens 3 :tool-call? true)]
    (is (= 2 (count (s/messages s2))))
    (is (= 8 (s/total-tokens s2)))
    (is (= 5 (get-in s2 ["stats" "input_tokens"])))
    (is (= 3 (get-in s2 ["stats" "output_tokens"])))
    (is (= 1 (get-in s2 ["stats" "request_count"])))
    (is (= 1 (get-in s2 ["stats" "tool_calls"])))
    (is (= [{"role" "user" "content" "hello there friend"}
            {"role" "assistant" "content" "hi"}]
           (s/api-context s2)))))

;;; --- token limits (config) -------------------------------------------------
(deftest token-limits
  (is (= 8000 (config/get-token-limit "llama3.2:latest")))   ; llama3 substring
  (is (= 4096 (config/get-token-limit "llama2")))            ; llama substring
  (is (= 32000 (config/get-token-limit "qwen3-coder:latest")))
  (is (= 1000000 (config/get-token-limit "gemini-2.5-flash")))
  (is (= 200000 (config/get-token-limit "claude-opus"))))

;;; --- threshold crossing (pure) ---------------------------------------------
(deftest thresholds
  (testing "75/90/95 fire in order, once each"
    (is (= ["warning"] (:crossed (s/check-thresholds 0.80 #{}))))
    (is (= ["high" "warning"] (:crossed (s/check-thresholds 0.92 #{}))))
    (is (= ["critical" "high" "warning"] (:crossed (s/check-thresholds 0.99 #{}))))
    (is (= [] (:crossed (s/check-thresholds 0.50 #{})))))
  (testing "already-fired levels do not re-fire"
    (is (= ["high"] (:crossed (s/check-thresholds 0.92 #{"warning"}))))
    (is (= [] (:crossed (s/check-thresholds 0.92 #{"warning" "high"}))))))

;;; --- compaction strategies -------------------------------------------------
(def msgs
  (into [{"role" "system" "content" "SYS" "tokens" 5}]
        (for [i (range 20)]
          {"role" (if (even? i) "user" "assistant")
           "content" (str "message " i) "tokens" 10})))

(deftest compact-truncate-keeps-system+recent
  (let [out (s/compact-truncate msgs 5)]
    (is (= 5 (count out)))
    (is (= "system" (get (first out) "role")))            ; system retained
    (is (= "message 19" (get (last out) "content")))))     ; most recent kept

(deftest compact-token-limit-budget
  (let [out (s/compact-token-limit msgs 50)]              ; 5 sys + ~4 others
    (is (= "system" (get (first out) "role")))
    (is (<= (reduce + 0 (map s/message-tokens out)) 50))
    (is (= "message 19" (get (last out) "content")))))

(deftest compact-summarize-injects-summary
  (let [out (s/compact-summarize msgs 5)]
    (is (= "system" (get (first out) "role")))
    (is (re-find #"Context Summary" (get (first out) "content")))
    (is (= 6 (count out)))                                ; 1 summary + 5 recent
    (is (= "message 19" (get (last out) "content")))))

;;; --- auto-compaction triggers at threshold ---------------------------------
(deftest maybe-compact-triggers
  (testing "below threshold -> no compaction"
    (let [small (reduce #(s/add-message %1 "user" %2 :tokens 10)
                        (s/new-session {:model "llama"}) ; limit 4096
                        (repeat 5 "x"))
          [s' desc] (s/maybe-compact small)]
      (is (nil? desc))
      (is (= s' small))))
  (testing "above threshold -> compaction reduces tokens"
    (let [big (reduce #(s/add-message %1 (if (even? %2) "user" "assistant")
                                      (str "m" %2) :tokens 200)
                      (s/new-session {:model "llama"})    ; 0.8*4096 = 3276
                      (range 30))                          ; 30*200 = 6000 tokens
          before (s/total-tokens big)
          [s' desc] (s/maybe-compact big)]
      (is (> before 3276))
      (is (string? desc))
      (is (< (s/total-tokens s') before)))))

;;; --- persistence round-trip (redirected dir) -------------------------------
(deftest save-load-roundtrip
  (let [tmp (str (System/getProperty "java.io.tmpdir")
                 "/sage-clj-sess-" (System/currentTimeMillis))]
    (with-redefs [config/sessions-dir (fn [] tmp)]
      (let [sess (-> (s/new-session {:name "round" :model "llama3"})
                     (s/add-message "user" "remember this" :tokens 4)
                     (s/add-message "assistant" "ok" :tokens 1))
            path (s/save-session sess)
            loaded (s/load-session-file "round")]
        (is (.exists (clojure.java.io/file path)))
        (is (= sess loaded))                                ; byte-faithful round-trip
        (is (= 2 (count (s/messages loaded))))
        (is (some #{"round"} (s/list-sessions)))
        (is (nil? (s/load-session-file "does-not-exist")))))))
