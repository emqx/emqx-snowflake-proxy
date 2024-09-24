(ns emqx.adapter-test
  (:require
   [cats.monad.either :as e :refer [right]]
   [clojure.string :as str]
   [clojure.test :refer [deftest are is testing]]
   [emqx.adapter :as adapter]
   [matcher-combinators.test]))

(deftest channel-data-in
  (testing "invalid json"
    (let [res (adapter/channel-data-in (.getBytes "{"))]
      (is (e/left? res))
      (e/branch-left
       res
       (fn [res]
         (is (str/starts-with? res "bad input: failure parsing json"))))))
  (testing "input is not a map"
    (are [input] (let [res (adapter/channel-data-in (.getBytes input))]
                   (is (e/left? res))
                   (e/branch-left
                    res
                    (fn [res]
                      (is (str/starts-with? res "bad input: should be a single JSON object")))))
      "[]"
      "[{}]"
      "1"
      "\"string\""
      "null"
      "1.23"))
  (testing "valid input"
    (are [x y] (= (right x) (adapter/channel-data-in (.getBytes y)))
      {} "{}"
      {"a" 10} "{\"a\":10}")))

(deftest mqtt-client-config
  (testing "optional username and password"
    (is (= {} (-> (adapter/mqtt-client-config {})
                  :opts
                  (select-keys [:username :password]))))
    (is (= {:username "foo"} (-> (adapter/mqtt-client-config {:username "foo"})
                                 :opts
                                 (select-keys [:username :password]))))
    ;; not very useful, but...
    (is (= {:password "bar"} (-> (adapter/mqtt-client-config {:password "bar"})
                                 :opts
                                 (select-keys [:username :password]))))
    (is (= {:username "foo"
            :password "bar"} (-> (adapter/mqtt-client-config {:username "foo"
                                                              :password "bar"})
                                 :opts
                                 (select-keys [:username :password]))))))
