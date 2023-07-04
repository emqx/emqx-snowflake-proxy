(ns emqx.adapter-test
  (:require
   [cats.monad.either :as e :refer [right]]
   [clojure.test :refer [deftest is testing]]
   [emqx.adapter :as adapter]
   [matcher-combinators.test]))

(deftest upsert-channel-in-test
  (let [chan-name "my_channel"
        valid-chan-params {:database "TESTDATABASE"
                           :schema "PUBLIC"
                           :table "TESTTABLE"
                           :on_error "continue"}]
    (testing "valid params for upsert"
      (let [res (adapter/upsert-channel-in {:path-params {:chan-name chan-name}
                                            :json-params valid-chan-params})]
        (is (= (right {:chan-name chan-name
                       :database (:database valid-chan-params)
                       :schema (:schema valid-chan-params)
                       :table (:table valid-chan-params)
                       :on-error :continue})
               res)))
      (let [params (assoc valid-chan-params :on_error "abort")
            res (adapter/upsert-channel-in {:path-params {:chan-name chan-name}
                                            :json-params params})]
        (is (= (right {:chan-name chan-name
                       :database (:database valid-chan-params)
                       :schema (:schema valid-chan-params)
                       :table (:table valid-chan-params)
                       :on-error :abort})
               res))))
    (testing "missing params for upsert"
      (doseq [k (keys valid-chan-params)]
        (let [invalid-chan-params (dissoc valid-chan-params k)
              res (adapter/upsert-channel-in {:path-params {:chan-name chan-name}
                                              :json-params invalid-chan-params})]
          (is (e/left? res)))))
    (testing "bad param types for upsert"
      (doseq [k (keys valid-chan-params)]
        (let [invalid-chan-params (assoc valid-chan-params k nil)
              res (adapter/upsert-channel-in {:path-params {:chan-name chan-name}
                                              :json-params invalid-chan-params})]
          (is (e/left? res))))
      (let [invalid-chan-params (assoc valid-chan-params :on_error "dunno")
            res (adapter/upsert-channel-in {:path-params {:chan-name chan-name}
                                            :json-params invalid-chan-params})]
        (is (e/left? res))))))

(deftest insert-rows-in-test
  (testing "valid inputs"
    (let [valid-cases {:empty-vec []
                       :one-map-int [{"c1" 123}]
                       :one-map-float [{"c1" 1.23}]
                       :one-map-str [{"c1" "str"}]
                       :one-map-bool [{"c1" true}]
                       :one-map-null [{"c1" nil}]
                       :one-map-array [{"c1" ["a" 123 1.23 true nil [] {}]}]
                       :one-map-nested [{"c1" {"a" 123}}]
                       :two-maps [{"c1" 123 "c2" 1.23} {"c1" 321}]}]
      (doseq [[name rows] valid-cases]
        (is (= (right {:rows rows})
               (adapter/insert-rows-in {:json-params {"rows" rows}}))
            {:case name}))))
  (testing "invalid inputs"
    (let [invalid-cases {:one-empty-map [{}]
                         :non-str-key [{:c1 123}]
                         :weird-val1 [{:c1 (fn [] true)}]
                         :mixed1 [{"c1" 123} {} {"c2" 321}]
                         :mixed2 [{"c1" 123} {:c1 123} {"c2" 321}]}]
      (doseq [[name rows] invalid-cases]
        (is (e/left? (adapter/insert-rows-in {:json-params {"rows" rows}})) {:case name})))))
