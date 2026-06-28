(ns sajure.repl-test
  "§5 agent loop + slash-command dispatch. The provider is MOCKED (an injected
  chat-fn) — we test the loop/wiring + the taint wrap on the own-LLM path, not
  the network."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sajure.repl :as repl]
            [sajure.session :as s]
            [sajure.taint :as taint]
            [sajure.tools :as tools]))

(def reg tools/default-registry)

(defn- user-tool-results
  "The wrapped tool-result user messages produced during a turn."
  [session]
  (->> (s/messages session)
       (filter #(and (= "user" (get % "role"))
                     (str/includes? (get % "content") "<tool-result")))
       (map #(get % "content"))))

;;; --- the tool-call loop executes + taint-wraps -----------------------------
(deftest tool-loop-executes-and-taint-wraps
  (let [calls (atom 0)
        seen (atom [])
        ;; turn 1 -> ask for echo; turn 2 -> final answer
        chat-fn (fn [_model _msgs _reg]
                  (if (= 1 (swap! calls inc))
                    {:content "" :completion-tokens 1
                     :tool-calls [{:name "echo" :arguments {"text" "PWN]]>inject"}}]}
                    {:content "final answer" :tool-calls [] :completion-tokens 2}))
        ctx {:registry reg :yolo? false :chat-fn chat-fn
             :on-tool (fn [_ w] (swap! seen conj w))}
        [sess' final] (repl/run-turn ctx (s/new-session {:model "llama3"}) "echo please")]
    (is (= "final answer" final))
    (is (= 2 @calls))                                       ; initial + re-prompt
    (let [wrapped (first (user-tool-results sess'))]
      (is (some? wrapped))
      (is (str/includes? wrapped "<tool-result name=\"echo\" safe=\"true\""))
      (is (str/includes? wrapped "<![CDATA["))
      ;; byte-faithful: the breakout payload survives the wrap intact
      (is (= "PWN]]>inject" (taint/extract-cdata-text wrapped))))
    (is (= 1 (count @seen)))))

;;; --- permission boundary inside the loop -----------------------------------
(deftest tool-loop-denies-unsafe-without-yolo
  (let [calls (atom 0)
        chat-fn (fn [_ _ _]
                  (if (= 1 (swap! calls inc))
                    {:content "" :tool-calls [{:name "write_file"
                                               :arguments {"path" "/tmp/x" "content" "y"}}]
                     :completion-tokens 1}
                    {:content "done" :tool-calls [] :completion-tokens 1}))
        ctx {:registry reg :yolo? false :chat-fn chat-fn}
        [sess' _] (repl/run-turn ctx (s/new-session {:model "llama3"}) "write it")]
    (is (str/includes? (taint/extract-cdata-text (first (user-tool-results sess')))
                       "Permission denied")))
  (testing "YOLO permits the unsafe tool"
    (let [calls (atom 0)
          chat-fn (fn [_ _ _]
                    (if (= 1 (swap! calls inc))
                      {:content "" :tool-calls [{:name "write_file"
                                                 :arguments {"path" "/tmp/x" "content" "y"}}]
                       :completion-tokens 1}
                      {:content "done" :tool-calls [] :completion-tokens 1}))
          ctx {:registry reg :yolo? true :chat-fn chat-fn}
          [sess' _] (repl/run-turn ctx (s/new-session {:model "llama3"}) "write it")]
      (is (str/includes? (taint/extract-cdata-text (first (user-tool-results sess')))
                         "would write")))))

;;; --- safety caps -----------------------------------------------------------
(deftest degenerate-loop-stops
  (let [calls (atom 0)
        ;; always the SAME tool+args -> degenerate; must stop
        chat-fn (fn [_ _ _] (swap! calls inc)
                  {:content "loop" :completion-tokens 1
                   :tool-calls [{:name "echo" :arguments {"text" "same"}}]})
        ctx {:registry reg :yolo? false :chat-fn chat-fn}
        [_ _] (repl/run-turn ctx (s/new-session {:model "llama3"}) "go")]
    (is (<= @calls (+ 2 repl/max-same-tool-repeats)))))

(deftest iteration-cap-stops
  (let [calls (atom 0)
        ;; distinct args each time so degenerate never trips -> hits iter cap
        chat-fn (fn [_ _ _] (let [n (swap! calls inc)]
                              {:content "x" :completion-tokens 1
                               :tool-calls [{:name "echo" :arguments {"text" (str n)}}]}))
        ctx {:registry reg :yolo? false :chat-fn chat-fn}
        [_ _] (repl/run-turn ctx (s/new-session {:model "llama3"}) "go")]
    (is (= (inc repl/max-tool-iterations) @calls))))

;;; --- slash-command dispatch ------------------------------------------------
(deftest slash-dispatch
  (let [ctx {:registry reg}
        sess (-> (s/new-session {:model "llama3"})
                 (s/add-message "user" "hi" :tokens 4))]
    (testing "/help /version /tools"
      (is (str/includes? (:output (repl/handle-command ctx sess "/help")) "/exit"))
      (is (str/includes? (:output (repl/handle-command ctx sess "/version")) "v2"))
      (is (str/includes? (:output (repl/handle-command ctx sess "/tools")) "echo")))
    (testing "/model shows then sets"
      (is (str/includes? (:output (repl/handle-command ctx sess "/model")) "llama3"))
      (is (= "gpt-4o" (get (:session (repl/handle-command ctx sess "/model gpt-4o")) "model"))))
    (testing "/reset clears messages"
      (is (= 0 (count (s/messages (:session (repl/handle-command ctx sess "/reset")))))))
    (testing "/stats /context"
      (is (str/includes? (:output (repl/handle-command ctx sess "/stats")) "Session:"))
      (is (str/includes? (:output (repl/handle-command ctx sess "/context")) "tokens")))
    (testing "/exit sets exit?"
      (is (true? (:exit? (repl/handle-command ctx sess "/exit")))))
    (testing "unknown command handled with hint"
      (let [r (repl/handle-command ctx sess "/bogus")]
        (is (:handled? r))
        (is (str/includes? (:output r) "Unknown command"))))
    (testing "non-command is NOT handled (falls through to chat)"
      (is (false? (:handled? (repl/handle-command ctx sess "just chatting")))))))

(deftest compact-command
  (let [sess (reduce #(s/add-message %1 "user" (str "m" %2) :tokens 10)
                     (s/new-session {:model "llama3"}) (range 20))
        r (repl/handle-command {:registry reg} sess "/compact 5")]
    (is (= 5 (count (s/messages (:session r)))))
    (is (str/includes? (:output r) "Compacted"))))
