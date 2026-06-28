(ns sajure.json-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sajure.json :as json]))

(def gen-scalar
  (gen/one-of
   [gen/string-ascii
    (gen/fmap long gen/large-integer)
    (gen/fmap #(/ % 100.0) (gen/choose -1000000 1000000))
    (gen/elements [true false json/null])]))

(def gen-json
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/vector inner 0 4)
       (gen/map gen/string-ascii inner {:max-elements 4})]))
   gen-scalar))

(defspec roundtrip-write-read 300
  (prop/for-all [v gen-json]
    (= v (json/read-str (json/write-str v)))))

(deftest null-vs-missing-vs-empty
  (testing "JSON null parses to the sentinel, distinct from Clojure nil/absent"
    (let [m (json/read-str "{\"a\":null,\"b\":1}")]
      (is (json/json-null? (get m "a")))
      (is (contains? m "a"))
      (is (not (contains? m "c")))
      (is (nil? (get m "c")))                ; absent -> nil
      (is (not (json/json-null? (get m "c"))))))
  (testing "empty object is an empty map, not null"
    (is (= {} (json/read-str "{}")))
    (is (not (json/json-null? (json/read-str "{}")))))
  (testing "both nil and the sentinel serialize to null"
    (is (= "null" (json/write-str nil)))
    (is (= "null" (json/write-str json/null)))))

(deftest malformed-throws
  (doseq [bad ["" "   " "{bad}" "[1,2" "nul" "{\"a\"}" "\"unterminated"]]
    (is (thrown? clojure.lang.ExceptionInfo (json/read-str bad))
        (str "should reject: " (pr-str bad)))))

(deftest escaping
  (is (= "a\nb\t\"c\\d" (json/read-str (json/write-str "a\nb\t\"c\\d"))))
  (is (= "" (json/read-str (json/write-str "")))))
