(ns sajure.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sajure.tools :as tools]))

(def nul (str (char 0)))

(defmacro with-ws [ws & body]
  `(with-redefs [tools/workspace (fn [] ~ws)] ~@body))

;;; --- totality: never throws, always boolean --------------------------------
(defspec safe-path-total 500
  (prop/for-all [s gen/string]
    (boolean? (with-ws "/ws" (tools/safe-path? s)))))

;;; --- NUL rejection (property) ----------------------------------------------
(defspec safe-path-rejects-nul 300
  (prop/for-all [a gen/string-ascii b gen/string-ascii]
    (false? (with-ws "/ws" (tools/safe-path? (str a nul b))))))

;;; --- ".." rejection (property) ---------------------------------------------
(defspec safe-path-rejects-dotdot 300
  (prop/for-all [a gen/string-ascii b gen/string-ascii]
    (false? (with-ws "/ws" (tools/safe-path? (str a ".." b))))))

(deftest safe-path-examples
  (with-ws "/ws"
    (testing "empty / non-string rejected"
      (is (false? (tools/safe-path? "")))
      (is (false? (tools/safe-path? nil)))
      (is (false? (tools/safe-path? 42))))
    (testing "PER-TOKEN .env rule: ==.env OR starts-with .env. (NOT substring)"
      (is (false? (tools/safe-path? ".env")))
      (is (false? (tools/safe-path? "foo/.env")))
      (is (false? (tools/safe-path? ".env.local")))        ; the bug the fix targets
      (is (false? (tools/safe-path? ".env.production")))
      (is (false? (tools/safe-path? "config/.env.staging")))
      (is (true?  (tools/safe-path? "my.env")))            ; not a segment
      (is (true?  (tools/safe-path? "envfile")))
      (is (true?  (tools/safe-path? "src/.environment"))))  ; .environment starts ".env" but not ".env." or "==.env"
    (testing ".git/.ssh/.gnupg are EXACT segment (allow .gitignore)"
      (is (false? (tools/safe-path? ".git/config")))
      (is (false? (tools/safe-path? "a/.ssh/id_rsa")))
      (is (false? (tools/safe-path? "x/.gnupg/key")))
      (is (true?  (tools/safe-path? ".gitignore")))
      (is (true?  (tools/safe-path? ".gitattributes")))
      (is (true?  (tools/safe-path? "gitconfig")))
      (is (true?  (tools/safe-path? "src/foo.clj"))))
    (testing "block applies to ALL paths INCLUDING /tmp"
      (is (false? (tools/safe-path? "/tmp/.ssh/id_rsa")))
      (is (false? (tools/safe-path? "/tmp/.env")))
      (is (false? (tools/safe-path? "/tmp/x/.env.local")))
      (is (true?  (tools/safe-path? "/tmp/scratch"))))
    (testing "relative resolves under workspace; /tmp ok; others rejected"
      (is (true?  (tools/safe-path? "notes/todo.txt")))
      (is (true?  (tools/safe-path? "/ws/sub/file")))
      (is (false? (tools/safe-path? "/etc/passwd")))
      (is (false? (tools/safe-path? "/home/other/x"))))))

;;; --- per-token blocked-segment? property -----------------------------------
(defspec env-local-always-blocked 200
  (prop/for-all [suffix (gen/such-that #(not (str/blank? %)) gen/string-alphanumeric)]
    (and (false? (with-ws "/ws" (tools/safe-path? (str ".env." suffix))))
         (true?  (tools/blocked-segment? (str ".env." suffix)))
         ;; `my.env`-style (non-segment) variants stay allowed
         (false? (tools/blocked-segment? (str suffix ".env"))))))

;;; --- permission model ------------------------------------------------------
(deftest permission-model
  (let [reg tools/default-registry
        echo (tools/find-tool reg "echo")
        wf   (tools/find-tool reg "write_file")]
    (is (tools/allowed? echo))                      ; safe always allowed
    (is (not (tools/allowed? wf)))                  ; unsafe denied by default
    (is (tools/allowed? wf true))                   ; confirmation permits
    (with-redefs [tools/yolo? (fn [] true)]
      (is (tools/allowed? wf)))))                   ; YOLO permits all

(deftest registry-is-immutable-data
  (is (vector? tools/default-registry))
  (is (every? map? tools/default-registry))
  (is (= "echo" (:name (tools/find-tool tools/default-registry "echo"))))
  (is (nil? (tools/find-tool tools/default-registry "nope"))))
