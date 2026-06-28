(ns sajure.mcp-client-test
  "§4 MCP client — discovery parsing, JSON-RPC framing, tool registration, and a
  client<->server interop check driven through our own mcp-server/dispatch (no
  subprocess, no network)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sajure.json :as json]
            [sajure.mcp-client :as mc]
            [sajure.mcp-server :as srv]
            [sajure.tools :as tools]))

;;; --- discovery parsing -----------------------------------------------------
(def sample-config
  (json/read-str
   (str "{\"mcpServers\":{"
        "\"local-stdio\":{\"command\":\"sage\",\"args\":[\"mcp-server\"]},"
        "\"remote-sse\":{\"type\":\"sse\",\"url\":\"https://example.test/sse\"},"
        "\"url-only\":{\"url\":\"https://x.test/sse\"}}}")))

(deftest extract-servers-classifies-transport
  (let [servers (mc/extract-servers sample-config)
        by-name (into {} (map (juxt :name identity) servers))]
    (is (= 3 (count servers)))
    (is (= {:name "local-stdio" :transport :stdio :command "sage" :args ["mcp-server"]}
           (get by-name "local-stdio")))
    (is (= :sse (:transport (get by-name "remote-sse"))))
    (is (= "https://example.test/sse" (:url (get by-name "remote-sse"))))
    (is (= :sse (:transport (get by-name "url-only"))))))    ; url, no command -> SSE

(deftest discover-safe-on-junk
  (is (= [] (mc/discover nil)))
  (is (= [] (mc/discover {"no" "servers"}))))

;;; --- JSON-RPC framing ------------------------------------------------------
(deftest rpc-request-framing
  (let [{:keys [id line]} (mc/rpc-request "tools/list" {})
        m (json/read-str line)]
    (is (= "2.0" (get m "jsonrpc")))
    (is (= id (get m "id")))
    (is (= "tools/list" (get m "method")))
    (is (= {} (get m "params"))))
  (testing "ids are monotonic"
    (is (< (:id (mc/rpc-request "ping" {})) (:id (mc/rpc-request "ping" {}))))))

(deftest extract-tool-text-from-envelope
  (is (= "hello world"
         (mc/extract-tool-text {"content" [{"type" "text" "text" "hello "}
                                           {"type" "text" "text" "world"}]}))))

;;; --- registration: discovered tools default UNSAFE, exec calls remote ------
(deftest register-tools-defaults-unsafe
  (let [descriptors [{"name" "search" "description" "Web search"
                      "inputSchema" {"type" "object"
                                     "properties" {"q" {"type" "string"}}}}]
        calls (atom [])
        call-fn (fn [n a] (swap! calls conj [n a]) (str "result for " (get a "q")))
        registered (mc/register-tools "srv" descriptors call-fn)
        t (first registered)]
    (is (= "srv.search" (:name t)))
    (is (false? (:safe t)))                                 ; §4: MCP tools unsafe
    (is (str/includes? (:description t) "[srv]"))
    (is (= "result for cats" ((:exec t) {"q" "cats"})))     ; exec -> remote call
    (is (= [["search" {"q" "cats"}]] @calls))))

;;; --- interop: client registration over OUR OWN server's dispatch -----------
(deftest client-server-interop
  ;; The "remote" is our mcp-server/dispatch against the default registry with
  ;; unsafe exposed; the client registers + invokes a tool through it.
  (let [remote (fn [tname targs]
                 (let [resp (srv/dispatch {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                                           "params" {"name" tname "arguments" targs}}
                                          tools/default-registry true)]
                   (mc/extract-tool-text (get resp "result"))))
        descriptors (tools/to-schema tools/default-registry)
        registered (mc/register-tools "sage" descriptors remote)
        echo (first (filter #(= "sage.echo" (:name %)) registered))]
    (is (some? echo))
    (is (= "round-trip!" ((:exec echo) {"text" "round-trip!"})))))
