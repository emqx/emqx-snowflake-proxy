(ns emqx.http-test
  {:clj-kondo/config
   '{:linters
     {:unresolved-symbol {:exclude [(clojure.test/is [match?])]}}}}
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [emqx.channel :as chan]
   [emqx.http :as server]
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [io.pedestal.test :refer [response-for]]
   [matcher-combinators.test]))

(defn make-service
  []
  (::http/service-fn (http/create-servlet {::http/routes server/routes})))

(def url-for (route/url-for-routes server/routes))

(def ^:dynamic *service* nil)

(defn client-fixture
  [f]
  (chan/start-client)
  (f)
  (chan/stop-client))

(defn server-fixture
  [f]
  (binding [*service* (make-service)]
    (f))
  (doseq [chan-name (keys @chan/channels)]
    (chan/ensure-chan-deleted chan-name)))

(use-fixtures :once client-fixture)
(use-fixtures :each server-fixture)

(defn get-channel
  [chan-name]
  (response-for *service*
                :get (url-for ::server/get-channel
                              :path-params {:chan-name chan-name})))

(defn upsert-channel
  [chan-name chan-params]
  (response-for *service*
                :post (url-for ::server/upsert-channel
                               :path-params {:chan-name chan-name})
                :headers {"Content-Type" "application/json"}
                :body (json/generate-string chan-params)))

(defn delete-channel
  [chan-name]
  (response-for *service*
                :delete (url-for ::server/delete-channel
                                 :path-params {:chan-name chan-name})))

(deftest ^:integration channels-test
  (testing "create and delete channel"
    (let [chan-name "my_channel"]
      (is (= 404 (:status (get-channel chan-name))))
      (let [chan-params {"database" "TESTDATABASE"
                         "schema" "PUBLIC"
                         "table" "TESTTABLE"
                         "on_error" "continue"}
            resp (upsert-channel chan-name chan-params)]
        (is (= 200 (:status resp)))
        (is (match?
             (assoc chan-params
                    "chan_name" chan-name
                    "last_offset" any?)
             (-> resp
                 :body
                 json/parse-string)))
        (let [get-resp (get-channel chan-name)]
          (is (= 200 (:status get-resp)))
          (is (match?
               (assoc chan-params
                      "chan_name" chan-name
                      "last_offset" any?)
               (-> get-resp
                   :body
                   json/parse-string)))
          (testing "upserting the channel with same params doesn't recreate it"
            (let [change-triggered (atom false)
                  _ (add-watch chan/channels :test-upsert (fn [_k _r _o _n]
                                                            (reset! change-triggered true)))
                  resp (upsert-channel chan-name chan-params)]
              (is (= 200 (:status resp)))
              (is (not @change-triggered))
              (is (match?
                   (assoc chan-params
                          "chan_name" chan-name
                          "last_offset" any?)
                   (-> get-resp
                       :body
                       json/parse-string)))
              (remove-watch chan/channels :test-upsert)))
          (testing "upserting the channel with different params recreate it"
            (let [change-triggered (atom false)
                  new-chan-params (assoc chan-params "on_error" "abort")
                  _ (add-watch chan/channels :test-upsert (fn [_k _r _o _n]
                                                            (reset! change-triggered true)))
                  recreate-resp (upsert-channel chan-name new-chan-params)]
              (is (= 200 (:status recreate-resp)))
              (is @change-triggered)
              (is (match?
                   (assoc new-chan-params
                          "chan_name" chan-name
                          "last_offset" any?)
                   (-> recreate-resp
                       :body
                       json/parse-string)))
              (remove-watch chan/channels :test-upsert)))))
      (let [resp (delete-channel chan-name)]
        (is (= 204 (:status resp)))
        (is (= 404 (:status (get-channel chan-name)))))
      (testing "deleting again is idempotent"
        (let [resp (delete-channel chan-name)]
          (is (= 204 (:status resp))))))))

(deftest ^:integration channels-adapter-integration-test
  (testing "missing params for upsert"
    (let [chan-name "my_channel"
          valid-chan-params {"database" "TESTDATABASE"
                             "schema" "PUBLIC"
                             "table" "TESTTABLE"
                             "on_error" "continue"}]
      (doseq [k (keys valid-chan-params)]
        (let [invalid-chan-params (dissoc valid-chan-params k)
              resp (upsert-channel chan-name invalid-chan-params)]
          (is (= 400 (:status resp)))
          (is (match? {"error" "bad input"
                       "details" string?}
                      (-> resp
                          :body
                          json/parse-string)))))))
  (testing "bad param types for upsert"
    (let [chan-name "my_channel"
          valid-chan-params {"database" "TESTDATABASE"
                             "schema" "PUBLIC"
                             "table" "TESTTABLE"
                             "on_error" "continue"}]
      (doseq [k (keys valid-chan-params)]
        (let [invalid-chan-params (assoc valid-chan-params k nil)
              resp (upsert-channel chan-name invalid-chan-params)]
          (is (= 400 (:status resp)))
          (is (match? {"error" "bad input"
                       "details" string?}
                      (-> resp
                          :body
                          json/parse-string)))))
      (let [invalid-chan-params (assoc valid-chan-params "on_error" "dunno")
            resp (upsert-channel chan-name invalid-chan-params)]
        (is (= 400 (:status resp)))
        (is (match? {"error" "bad input"
                     "details" string?}
                    (-> resp
                        :body
                        json/parse-string)))))))

(defn insert-rows
  [chan-name body-params]
  (response-for *service*
                :post (url-for ::server/insert-rows
                               :path-params {:chan-name chan-name})
                :headers {"Content-Type" "application/json"}
                :body (json/generate-string body-params)))

(deftest ^:integration insert-rows-test
  (let [chan-name "my_channel"
        chan-params {"database" "TESTDATABASE"
                     "schema" "PUBLIC"
                     "table" "TESTTABLE"
                     "on_error" "continue"}
        valid-rows {"rows" [{"c1" 123}
                            {"c1" 234}]}]
    (testing "channel not created"
      (let [resp (insert-rows chan-name valid-rows)]
        (is (= 404 (:status resp)))))

    (is (= 200 (:status (upsert-channel chan-name chan-params))))

    (testing "inserting valid rows"
      (let [resp (insert-rows chan-name valid-rows)]
        (is (= 204 (:status resp))))
      (is (= 204 (:status (insert-rows chan-name {"rows" []})))))
    (testing "inserting invalid rows"
      (let [resp (insert-rows chan-name {"rows" [{"nonexistent" 123}]})]
        (is (= 400 (:status resp)))
        (is (match?
             {"errors" [{"row-index" 0
                         "message" #"The given row cannot be converted to the internal format: Extra columns"}]}
             (json/parse-string (:body resp)))))
      (let [resp (insert-rows chan-name {"rows" [{"c1" "wrong type"}]})]
        (is (= 400 (:status resp)))
        (is (match?
             {"errors" [{"row-index" 0
                         "message" #"The given row cannot be converted to the internal format due to invalid value"}]}
             (json/parse-string (:body resp)))))
      (let [resp (insert-rows chan-name {"rows" [{"c1" 123}
                                                 {"c1" "wrong type"}
                                                 {"c1" 234}]})]
        (is (= 400 (:status resp)))
        (is (match?
             {"errors" [{"row-index" 1
                         "message" #"The given row cannot be converted to the internal format due to invalid value"}]}
             (json/parse-string (:body resp))))))))
