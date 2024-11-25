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

(deftest channel-client-config->props
  (let [base-proxy-params {:host "proxy.host.com" :port 1234}
        base-params {:client-name "client-name"
                     :user "client-user"
                     :url "snowflake.url.com"
                     :private-key "secret-private-key"
                     :port 443
                     :host "snowflake.host"
                     :scheme "https"}]
    (testing "no proxy config"
      (let [props (adapter/channel-client-config->props base-params)]
        (is (= [{"scheme" "https"
                 "port" "443"
                 "host" "snowflake.host"
                 "private_key" "secret-private-key"
                 "user" "client-user"
                 "url" "snowflake.url.com"}
                nil]
               props))))
    (testing "proxy config, no username and password"
      (let [props (adapter/channel-client-config->props (assoc base-params :proxy base-proxy-params))]
        (is (= [{"scheme" "https"
                 "port" "443"
                 "host" "snowflake.host"
                 "private_key" "secret-private-key"
                 "user" "client-user"
                 "url" "snowflake.url.com"}
                {"http.useProxy" "true"
                 "http.proxyHost" "proxy.host.com"
                 "http.proxyPort" "1234"}]
               props))))
    (testing "proxy config, with username and password"
      (let [props (-> base-params
                      (assoc :proxy base-proxy-params)
                      (assoc-in [:proxy :user] "proxy-user")
                      (assoc-in [:proxy :password] "proxy-password")
                      adapter/channel-client-config->props)]
        (is (= [{"scheme" "https"
                 "port" "443"
                 "host" "snowflake.host"
                 "private_key" "secret-private-key"
                 "user" "client-user"
                 "url" "snowflake.url.com"}
                {"http.useProxy" "true"
                 "http.proxyHost" "proxy.host.com"
                 "http.proxyPort" "1234"
                 "http.proxyUser" "proxy-user"
                 "http.proxyPassword" "proxy-password"}]
               props))))))
