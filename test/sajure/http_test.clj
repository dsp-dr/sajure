(ns sajure.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [sajure.http :as http]))

(deftest retry-set
  (testing "retry set is 408/429/5xx ONLY"
    (doseq [c [408 429 500 502 503 599]] (is (http/retryable-code? c) (str c)))
    (testing "code 0 (connection failed) is NOT retried — fast-fail"
      (is (not (http/retryable-code? 0))))
    (doseq [c [200 400 401 403 404 300]] (is (not (http/retryable-code? c)) (str c)))))

(deftest retries-with-backoff
  (testing "transient code retried up to max, exponential backoff sleeps"
    (let [calls (atom 0)
          sleeps (atom [])
          resp (http/with-retry
                 (fn [] (swap! calls inc) {:code 500 :body "boom"})
                 2
                 (fn [ms] (swap! sleeps conj ms)))]
      (is (= 3 @calls))                 ; initial + 2 retries
      (is (= [250 500] @sleeps))        ; exp backoff base 250
      (is (= 500 (:code resp))))))

(deftest fast-fail-on-connection
  (testing "code 0 never retried regardless of max"
    (let [calls (atom 0)
          sleeps (atom 0)
          resp (http/with-retry
                 (fn [] (swap! calls inc) {:code 0 :body "no conn"})
                 5
                 (fn [_] (swap! sleeps inc)))]
      (is (= 1 @calls))
      (is (= 0 @sleeps))
      (is (= 0 (:code resp))))))

(deftest eventual-success
  (testing "stops retrying once a non-retry code arrives"
    (let [seq* (atom [429 429 200])
          calls (atom 0)
          resp (http/with-retry
                 (fn [] (swap! calls inc)
                   (let [c (first @seq*)] (swap! seq* rest) {:code c :body ""}))
                 5
                 (fn [_] nil))]
      (is (= 3 @calls))
      (is (= 200 (:code resp))))))
