(ns sajure.runner
  "Test runner that PROPAGATES failures — exits nonzero on any fail/error so a
  red property can never be masked as a green build (§11 'no masked greens')."
  (:require [clojure.test :as t]
            [sajure.json-test]
            [sajure.taint-test]
            [sajure.tools-test]
            [sajure.providers-test]
            [sajure.providers-chat-test]
            [sajure.http-test]
            [sajure.mcp-test]
            [sajure.mcp-client-test]
            [sajure.session-test]
            [sajure.repl-test]
            [sajure.attest-test]))

(def test-namespaces
  '[sajure.json-test
    sajure.taint-test
    sajure.tools-test
    sajure.providers-test
    sajure.providers-chat-test
    sajure.http-test
    sajure.mcp-test
    sajure.mcp-client-test
    sajure.session-test
    sajure.repl-test
    sajure.attest-test])

(defn -main [& _]
  (let [summary (apply t/run-tests test-namespaces)
        failed (+ (:fail summary 0) (:error summary 0))]
    (println)
    (println (format "==> %d assertions, %d failures, %d errors"
                     (:pass summary 0) (:fail summary 0) (:error summary 0)))
    (shutdown-agents)
    (System/exit (if (pos? failed) 1 0))))
