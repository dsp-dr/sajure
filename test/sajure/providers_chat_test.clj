(ns sajure.providers-chat-test
  "§2 provider chat layer — response NORMALIZATION + projection (the pure parts;
  no live LLM). Bodies are parsed with the real json reader so the null sentinel
  / number coercion behave exactly as on the wire."
  (:require [clojure.test :refer [deftest is testing]]
            [sajure.json :as json]
            [sajure.providers :as p]
            [sajure.tools :as tools]))

(deftest normalize-ollama-shape
  (let [body (str "{\"message\":{\"role\":\"assistant\",\"content\":\"hi\","
                  "\"tool_calls\":[{\"function\":{\"name\":\"echo\","
                  "\"arguments\":{\"text\":\"yo\"}}}]},"
                  "\"prompt_eval_count\":10,\"eval_count\":5}")
        r (p/normalize-ollama (json/read-str body))]
    (is (= "hi" (:content r)))
    (is (= [{:name "echo" :arguments {"text" "yo"}}] (:tool-calls r)))
    (is (= 10 (:prompt-tokens r)))
    (is (= 5 (:completion-tokens r)))
    (is (false? (:error? r)))))

(deftest normalize-ollama-no-tools
  (let [r (p/normalize-ollama (json/read-str
                               "{\"message\":{\"content\":\"plain\"},\"eval_count\":2}"))]
    (is (= "plain" (:content r)))
    (is (= [] (:tool-calls r)))))

(deftest normalize-openai-shape
  ;; OpenAI sends tool-call arguments as a JSON STRING — must be re-parsed.
  (let [body (str "{\"choices\":[{\"message\":{\"content\":\"ok\","
                  "\"tool_calls\":[{\"function\":{\"name\":\"echo\","
                  "\"arguments\":\"{\\\"text\\\":\\\"yo\\\"}\"}}]}}],"
                  "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}")
        r (p/normalize-openai (json/read-str body))]
    (is (= "ok" (:content r)))
    (is (= [{:name "echo" :arguments {"text" "yo"}}] (:tool-calls r)))
    (is (= 3 (:prompt-tokens r)))
    (is (= 2 (:completion-tokens r)))))

(deftest normalize-gemini-shape
  (let [body (str "{\"candidates\":[{\"content\":{\"parts\":["
                  "{\"text\":\"hello\"},"
                  "{\"functionCall\":{\"name\":\"echo\",\"args\":{\"text\":\"yo\"}}}]}}],"
                  "\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":4}}")
        r (p/normalize-gemini (json/read-str body))]
    (is (= "hello" (:content r)))
    (is (= [{:name "echo" :arguments {"text" "yo"}}] (:tool-calls r)))
    (is (= 7 (:prompt-tokens r)))
    (is (= 4 (:completion-tokens r)))))

(deftest gemini-message-conversion
  (testing "assistant -> model, system/user -> user, content -> parts/text"
    (is (= [{"role" "user" "parts" [{"text" "hi"}]}
            {"role" "model" "parts" [{"text" "yo"}]}
            {"role" "user" "parts" [{"text" "sys"}]}]
           (p/messages->gemini-contents
            [{"role" "user" "content" "hi"}
             {"role" "assistant" "content" "yo"}
             {"role" "system" "content" "sys"}])))))

(deftest tool-schema-projection
  (let [defs (p/tools->function-defs tools/default-registry)]
    (is (every? #(= "function" (get % "type")) defs))
    (is (= "echo" (get-in (first defs) ["function" "name"])))
    (is (contains? (get-in (first defs) ["function"]) "parameters"))))

(deftest provider-resolution
  (testing "host/model defaults per provider"
    (is (= "http://localhost:11434" (p/provider-host :ollama)))
    (is (= "llama3.2:latest" (p/provider-model :ollama)))
    (is (re-find #"googleapis" (p/provider-host :gemini)))))
